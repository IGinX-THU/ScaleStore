package com.storage.engine.controller;

import com.storage.engine.model.Policy;
import com.storage.engine.model.Response;
import com.storage.engine.service.PolicyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class PolicyController {

    @Autowired
    private PolicyService policyService;

    @GetMapping("/config/policies")
    public ResponseEntity<Response<Policy>> getPolicy() {
        return ResponseEntity.ok(Response.success(policyService.getPolicy()));
    }

    @PutMapping("/config/policies")
    public ResponseEntity<Response<Policy>> updatePolicy(@RequestBody Policy policy) {
        return ResponseEntity.ok(Response.success(policyService.updatePolicy(policy)));
    }
}
