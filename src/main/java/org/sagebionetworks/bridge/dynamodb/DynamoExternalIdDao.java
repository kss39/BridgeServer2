package org.sagebionetworks.bridge.dynamodb;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.sagebionetworks.bridge.BridgeConstants.API_MAXIMUM_PAGE_SIZE;
import static com.amazonaws.services.dynamodbv2.model.ComparisonOperator.BEGINS_WITH;
import static com.amazonaws.services.dynamodbv2.model.ComparisonOperator.NOT_NULL;
import static com.amazonaws.services.dynamodbv2.model.ComparisonOperator.NULL;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Resource;

import com.amazonaws.services.dynamodbv2.datamodeling.QueryResultPage;
import com.amazonaws.services.dynamodbv2.model.ReturnConsumedCapacity;
import com.google.common.util.concurrent.RateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.config.BridgeConfig;
import org.sagebionetworks.bridge.dao.ExternalIdDao;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.exceptions.ConcurrentModificationException;
import org.sagebionetworks.bridge.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.models.ForwardCursorPagedResourceList;
import org.sagebionetworks.bridge.models.ResourceList;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.ExternalIdentifier;
import org.sagebionetworks.bridge.models.accounts.ExternalIdentifierInfo;
import org.sagebionetworks.bridge.models.studies.Enrollment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBQueryExpression;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBSaveExpression;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.amazonaws.services.dynamodbv2.model.ExpectedAttributeValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

@Component
public class DynamoExternalIdDao implements ExternalIdDao {
    private static final Logger LOG = LoggerFactory.getLogger(DynamoExternalIdDao.class);
        
    static final String PAGE_SIZE_ERROR = "pageSize must be from 1-"+API_MAXIMUM_PAGE_SIZE+" records";
    static final int PAGE_SCAN_LIMIT = 200;
    static final String HEALTH_CODE = "healthCode";
    static final String IDENTIFIER = "identifier";
    static final String STUDY_ID = "substudyId";
    static final String APP_ID = "studyId";

    private RateLimiter getExternalIdRateLimiter;
    private DynamoDBMapper mapper;

    /** Gets the add limit and lock duration from Config. */
    @Autowired
    final void setBridgeConfig(BridgeConfig config) {
        setGetExternalIdRateLimiter(RateLimiter.create(config.getInt(EXTERNAL_ID_GET_RATE)));
    }
    
    // allow unit test to mock this
    final void setGetExternalIdRateLimiter(RateLimiter getExternalIdRateLimiter) {
        this.getExternalIdRateLimiter = getExternalIdRateLimiter;
    }

    @Resource(name = "externalIdDdbMapper")
    final void setMapper(DynamoDBMapper mapper) {
        this.mapper = mapper;
    }
    
    @Override
    public Optional<ExternalIdentifier> getExternalId(String appId, String externalId) {
        checkNotNull(appId);
        checkNotNull(externalId);
        
        DynamoExternalIdentifier key = new DynamoExternalIdentifier(appId, externalId);
        return Optional.ofNullable(mapper.load(key));
    }

    @Override
    public ForwardCursorPagedResourceList<ExternalIdentifierInfo> getExternalIds(String appId,
            String offsetKey, int pageSize, String idFilter, Boolean assignmentFilter) {

        if (pageSize < 1 || pageSize > API_MAXIMUM_PAGE_SIZE) {
            throw new BadRequestException("Invalid paging size: " + pageSize);
        }
        
        // The offset key is applied after the idFilter. If the offsetKey doesn't match the beginning
        // of the idFilter, the AWS SDK throws a validation exception. So when providing an idFilter and 
        // a paging offset, clear the offset (go back to the first page) if they don't match.
        String nextPageOffsetKey = offsetKey; 
        if (offsetKey != null && idFilter != null && !offsetKey.startsWith(idFilter)) {
            nextPageOffsetKey = null;
        }
        
        Set<String> callerStudies = BridgeUtils.getRequestContext().getCallerStudies();
        
        List<ExternalIdentifierInfo> externalIds = Lists.newArrayListWithCapacity(pageSize);
        
        // initial estimate: read capacity consumed will equal 1
        // see https://aws.amazon.com/blogs/developer/rate-limited-scans-in-amazon-dynamodb/        
        int capacityAcquired = 1;
        int capacityConsumed = 0;
        do {
            getExternalIdRateLimiter.acquire(capacityAcquired);
            
            DynamoDBQueryExpression<DynamoExternalIdentifier> query = createGetQuery(appId, nextPageOffsetKey,
                    PAGE_SCAN_LIMIT, idFilter, assignmentFilter);
            
            QueryResultPage<DynamoExternalIdentifier> queryResultPage = mapper.queryPage(
                    DynamoExternalIdentifier.class, query);
            for (ExternalIdentifier id : queryResultPage.getResults()) {
                if (externalIds.size() == pageSize) {
                    // return no more than pageSize externalIdentifiers
                    break;
                }
                
                // Users see all external IDs, but they don't see the study membership of an external ID
                // unless they meet the standard rules
                boolean visible = callerStudies.isEmpty() || callerStudies.contains(id.getStudyId());
                String studyId = (visible) ? id.getStudyId() : null;
                
                ExternalIdentifierInfo info = new ExternalIdentifierInfo(
                        id.getIdentifier(), studyId, id.getHealthCode() != null);
                externalIds.add(info);
            }
            capacityConsumed = queryResultPage.getConsumedCapacity().getCapacityUnits().intValue();
            LOG.debug("Capacity acquired: " + capacityAcquired + ", Consumed Capacity: " + capacityConsumed);
            
            // use capacity consumed by last request to as our estimate for the next request
            capacityAcquired = capacityConsumed;
            
            if (queryResultPage.getCount() > pageSize) {
                // we retrieved more records from Dynamo than we are returning
                nextPageOffsetKey = externalIds.get(pageSize - 1).getIdentifier();
            } else {
                // This is the last key, not the next key of the next page of records. It only exists if there's 
                // a record beyond the records we've converted to a page. Then get the last key in the list.
                Map<String, AttributeValue> lastEvaluated = queryResultPage.getLastEvaluatedKey();
                nextPageOffsetKey = lastEvaluated != null ? lastEvaluated.get(IDENTIFIER).getS() : null;
            }
        } while ((externalIds.size() < pageSize) && (nextPageOffsetKey != null));

        return new ForwardCursorPagedResourceList<>(externalIds, nextPageOffsetKey)
                .withRequestParam(ResourceList.OFFSET_KEY, offsetKey)
                .withRequestParam(ResourceList.PAGE_SIZE, pageSize)
                .withRequestParam(ResourceList.ID_FILTER, idFilter)
                .withRequestParam(ResourceList.ASSIGNMENT_FILTER, assignmentFilter);
    }

    @Override
    public void createExternalId(ExternalIdentifier externalId) {
        checkNotNull(externalId);
        
        mapper.save(externalId);
    }

    @Override
    public void deleteExternalId(ExternalIdentifier externalId) {
        checkNotNull(externalId);
        
        mapper.delete(externalId);
    }
    
    /**
     * There is a substantial amount of set-up that must occur before this call can be 
     * made, and the associated account record must be updated as well. See 
     * ParticipantService.beginAssignExternalId() which performs this setup, and is 
     * always called before the participant service calls this method. This method is 
     * not simply a method to update an external ID record.
     */
    @Override
    public void commitAssignExternalId(ExternalIdentifier externalId) {
        if (externalId != null) {
            DynamoExternalIdentifier key = new DynamoExternalIdentifier(
                    externalId.getAppId(), externalId.getIdentifier());
            if (externalId.getHealthCode() == null || mapper.load(key) == null) {
                throw new ConcurrentModificationException("External ID was concurrently deleted or assigned");
            }
            try {
                mapper.save(externalId, getHealthCodeAssignedExpression(false));
            } catch(ConditionalCheckFailedException e) {
                throw new EntityAlreadyExistsException(ExternalIdentifier.class, IDENTIFIER, externalId.getIdentifier());
            }
        }
    }

    @Override
    public void unassignExternalId(Account account, String externalId) {
        checkNotNull(account);
        checkArgument(isNotBlank(externalId));
        
        Optional<ExternalIdentifier> optionalId = getExternalId(account.getAppId(), externalId);
        
        if (!optionalId.isPresent()) {
            return;
        }
        ExternalIdentifier identifier = optionalId.get();
        if (account.getHealthCode().equals(identifier.getHealthCode())) {
            identifier.setHealthCode(null);
            mapper.save(identifier);
        }
        if (identifier.getStudyId() != null) {
            Enrollment enrollment = Enrollment.create(account.getAppId(),
                    identifier.getStudyId(), account.getId(), identifier.getIdentifier());
            account.getEnrollments().remove(enrollment);
        }
    }
    
    private DynamoDBQueryExpression<DynamoExternalIdentifier> createGetQuery(String appId, String offsetKey,
            int pageSize, String idFilter, Boolean assignmentFilter) {
        
        DynamoDBQueryExpression<DynamoExternalIdentifier> query =
                new DynamoDBQueryExpression<DynamoExternalIdentifier>();
        if (idFilter != null) {
            query.withRangeKeyCondition(IDENTIFIER, new Condition()
                    .withAttributeValueList(new AttributeValue().withS(idFilter))
                    .withComparisonOperator(BEGINS_WITH));
        }
        if (assignmentFilter != null) {
            query.withQueryFilterEntry(HEALTH_CODE, new Condition()
                .withComparisonOperator(assignmentFilter.booleanValue() ? NOT_NULL : NULL));
        }
        query.withHashKeyValues(new DynamoExternalIdentifier(appId, null)); // no healthCode.

        if (offsetKey != null) {
            Map<String, AttributeValue> map = new HashMap<>();
            map.put(APP_ID, new AttributeValue().withS(appId));
            map.put(IDENTIFIER, new AttributeValue().withS(offsetKey));
            query.withExclusiveStartKey(map);
        }

        query.withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
        query.withConsistentRead(true);
        query.withLimit(pageSize);
        return query;
    }
    
    private DynamoDBSaveExpression getHealthCodeAssignedExpression(boolean healthCodeAssigned) {
        Map<String, ExpectedAttributeValue> map = ImmutableMap.of(
                HEALTH_CODE, new ExpectedAttributeValue().withExists(healthCodeAssigned));

        return new DynamoDBSaveExpression().withExpected(map);
    }
    
}
