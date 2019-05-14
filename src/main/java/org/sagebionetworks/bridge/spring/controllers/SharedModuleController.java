package org.sagebionetworks.bridge.spring.controllers;

import static org.sagebionetworks.bridge.Roles.DEVELOPER;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;

import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.sharedmodules.SharedModuleImportStatus;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.services.SharedModuleService;

@CrossOrigin
@RestController
public class SharedModuleController extends BaseController {
    
    private SharedModuleService moduleService;

    /** Shared Module Service, configured by Spring. */
    @Autowired
    void setModuleService(SharedModuleService moduleService) {
        this.moduleService = moduleService;
    }

    /** Imports a specific module version into the current study. */
    public SharedModuleImportStatus importModuleByIdAndVersion(String moduleId, int moduleVersion) {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        StudyIdentifier studyId = session.getStudyIdentifier();

        return moduleService.importModuleByIdAndVersion(studyId, moduleId, moduleVersion);
    }

    /** Imports the latest published version of a module into the current study. */
    public SharedModuleImportStatus importModuleByIdLatestPublishedVersion(String moduleId) {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        StudyIdentifier studyId = session.getStudyIdentifier();

        return moduleService.importModuleByIdLatestPublishedVersion(studyId, moduleId);
    }
}
