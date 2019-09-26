package org.sagebionetworks.bridge.spring.controllers;

import static org.sagebionetworks.bridge.BridgeConstants.API_DEFAULT_PAGE_SIZE;
import static org.sagebionetworks.bridge.Roles.ADMIN;
import static org.sagebionetworks.bridge.Roles.DEVELOPER;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.models.GuidVersionHolder;
import org.sagebionetworks.bridge.models.ResourceList;
import org.sagebionetworks.bridge.models.StatusMessage;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.files.FileMetadata;
import org.sagebionetworks.bridge.services.FileService;

@CrossOrigin
@RestController
public class FileMetadataController extends BaseController {
    
    static final StatusMessage DELETE_MSG = new StatusMessage("File metadata and revisions deleted.");
    private FileService fileService;
    
    @Autowired
    final void setFileService(FileService fileService) {
        this.fileService = fileService;
    }
    
    @GetMapping("/v3/files")
    public ResourceList<FileMetadata> getFiles(@RequestParam(required = false) String offsetBy, @RequestParam(required = false) String pageSize, 
            @RequestParam(required = false) String includeDeleted) {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        
        int offsetInt = BridgeUtils.getIntOrDefault(offsetBy, 0);
        int pageSizeInt = BridgeUtils.getIntOrDefault(pageSize, API_DEFAULT_PAGE_SIZE);
        boolean includeDeletedBool = Boolean.valueOf(includeDeleted);
        
        return fileService.getFiles(session.getStudyIdentifier(), offsetInt, pageSizeInt, includeDeletedBool);
    }
    
    @PostMapping("/v3/files")
    @ResponseStatus(HttpStatus.CREATED)
    public GuidVersionHolder createFile() {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        
        FileMetadata file = parseJson(FileMetadata.class);
        FileMetadata updated = fileService.createFile(session.getStudyIdentifier(), file);
        
        return new GuidVersionHolder(updated.getGuid(), Long.valueOf(updated.getVersion()));
    }
    
    @GetMapping("/v3/files/{guid}")
    public FileMetadata getFile(@PathVariable String guid) {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        
        return fileService.getFile(session.getStudyIdentifier(), guid);
    }
    
    @PostMapping("/v3/files/{guid}")
    public GuidVersionHolder updateFile(@PathVariable String guid) {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        
        FileMetadata file = parseJson(FileMetadata.class);
        file.setGuid(guid);
        FileMetadata updated = fileService.updateFile(session.getStudyIdentifier(), file);
        
        return new GuidVersionHolder(updated.getGuid(), Long.valueOf(updated.getVersion()));
    }
    
    @DeleteMapping("/v3/files/{guid}")
    public StatusMessage deleteFile(@PathVariable String guid,
            @RequestParam(defaultValue = "false") String physical) {
        UserSession session = getAuthenticatedSession(DEVELOPER, ADMIN);
        
        if ("true".equals(physical) && session.isInRole(ADMIN)) {
            fileService.deleteFilePermanently(session.getStudyIdentifier(), guid);
        } else {
            fileService.deleteFile(session.getStudyIdentifier(), guid);
        }
        return DELETE_MSG;
    }
}
