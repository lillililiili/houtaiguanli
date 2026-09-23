package com.uav.lowaltitude.modules.directory.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.*;
import com.uav.lowaltitude.modules.directory.application.DirectoryService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController @RequestMapping("/api/v1")
public class DirectoryController {
 private final DirectoryService directory;
 public DirectoryController(DirectoryService directory){this.directory=directory;}
 @GetMapping("/organization-profiles") public ApiResponse<Page<Organization>> organizations(@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok(directory.organizations(keyword,page,size));}
 @GetMapping("/organization-profiles/{id}") public ApiResponse<Organization> organization(@PathVariable String id){return ApiResponse.ok(directory.organization(id));}
 @PostMapping("/organization-profiles") public ApiResponse<Organization> createOrganization(@Valid @RequestBody OrganizationInput body,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(directory.createOrganization(body,key));}
 @PatchMapping("/organization-profiles/{id}") public ApiResponse<Organization> updateOrganization(@PathVariable String id,@Valid @RequestBody OrganizationInput body,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(directory.updateOrganization(id,body,key));}
 @GetMapping("/contacts") public ApiResponse<Page<Contact>> contacts(@RequestParam(name="org_id",required=false) String org,@RequestParam(required=false) String keyword,@RequestParam(required=false) Boolean enabled,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok(directory.contacts(org,keyword,enabled,page,size));}
 @GetMapping("/contacts/{id}") public ApiResponse<Contact> contact(@PathVariable String id){return ApiResponse.ok(directory.contact(id));}
 @PostMapping("/contacts") public ApiResponse<Contact> createContact(@Valid @RequestBody ContactInput body,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(directory.createContact(body,key));}
 @PatchMapping("/contacts/{id}") public ApiResponse<Contact> updateContact(@PathVariable String id,@Valid @RequestBody ContactInput body,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(directory.updateContact(id,body,key));}
 @GetMapping("/plan-source-bindings") public ApiResponse<Page<SourceBinding>> bindings(@RequestParam(name="org_id",required=false) String org,@RequestParam(name="source_id",required=false) String source,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok(directory.bindings(org,source,keyword,page,size));}
 @PostMapping("/plan-source-bindings") public ApiResponse<SourceBinding> createBinding(@Valid @RequestBody BindingInput body,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(directory.createBinding(body,key));}
 @PatchMapping("/plan-source-bindings/{id}") public ApiResponse<SourceBinding> updateBinding(@PathVariable String id,@Valid @RequestBody BindingInput body,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(directory.updateBinding(id,body,key));}
 @GetMapping("/directory-options") public ApiResponse<Page<Option>> options(@RequestParam String kind,@RequestParam(name="org_id",required=false) String org,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok(directory.options(kind,org,keyword,page,size));}
 @GetMapping("/flight-plans/{id}/subjects") public ApiResponse<Subjects> subjects(@PathVariable String id){return ApiResponse.ok(directory.subjects(id));}
 @PatchMapping("/flight-plans/{id}/subjects") public ApiResponse<Subjects> updateSubjects(@PathVariable String id,@Valid @RequestBody SubjectInput body,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(directory.updateSubjects(id,body,key));}
}
