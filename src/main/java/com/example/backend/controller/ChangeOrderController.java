package com.example.backend.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.backend.entity.ApiResponse;
import com.example.backend.entity.ChangeOrder;
import com.example.backend.service.ChangeOrderService;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"https://ikt-management-frontend.vercel.app", "http://localhost:5173"})
public class ChangeOrderController {

    @Autowired
    private ChangeOrderService changeOrderService;

    // GET all change orders for a project (by projectName as toggle ref)
    @GetMapping("/project-status/{projectName}/change-orders")
    public ResponseEntity<ApiResponse<List<ChangeOrder>>> getChangeOrdersByProjectName(
            @PathVariable String projectName) {
        List<ChangeOrder> changeOrders = changeOrderService.getChangeOrdersByProjectName(projectName);
        return ResponseEntity.ok(ApiResponse.success(changeOrders));
    }

    // GET single change order by ID
    @GetMapping("/change-orders/{id}")
    public ResponseEntity<ApiResponse<ChangeOrder>> getChangeOrderById(@PathVariable Long id) {
        ChangeOrder changeOrder = changeOrderService.getChangeOrderById(id);
        return ResponseEntity.ok(ApiResponse.success(changeOrder));
    }

    // POST create single change order (projectName is the toggle/ref key)
    @PostMapping("/project-status/{projectName}/change-orders")
    public ResponseEntity<ApiResponse<List<ChangeOrder>>> createChangeOrders(
            @PathVariable String projectName,
            @RequestBody Object body) {

        // Accept both single object and array from frontend
        List<ChangeOrder> result;
        if (body instanceof java.util.List) {
            @SuppressWarnings("unchecked")
            List<java.util.Map<String, Object>> list = (List<java.util.Map<String, Object>>) body;
            List<ChangeOrder> orders = list.stream()
                .map(m -> mapToChangeOrder(m))
                .collect(java.util.stream.Collectors.toList());
            result = changeOrderService.createBulkChangeOrder(projectName, orders);
        } else {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = (java.util.Map<String, Object>) body;
            ChangeOrder order = mapToChangeOrder(map);
            ChangeOrder created = changeOrderService.createChangeOrder(projectName, order);
            result = java.util.Collections.singletonList(created);
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(result.size() + " change order(s) created successfully", result));
    }

    // PUT update change order — uses change order ID, projectName kept as context ref
    @PutMapping("/project-status/{projectName}/change-orders/{id}")
    public ResponseEntity<ApiResponse<ChangeOrder>> updateChangeOrder(
            @PathVariable String projectName,
            @PathVariable Long id,
            @RequestBody ChangeOrder changeOrder) {
        ChangeOrder updatedOrder = changeOrderService.updateChangeOrder(id, changeOrder);
        return ResponseEntity.ok(ApiResponse.success("Change order updated successfully", updatedOrder));
    }

    // DELETE change order by ID
    @DeleteMapping("/change-orders/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteChangeOrder(@PathVariable Long id) {
        changeOrderService.deleteChangeOrder(id);
        return ResponseEntity.ok(ApiResponse.deleteSuccess(id));
    }

    // ── Helper: map raw JSON object to ChangeOrder entity ──────────────────
    private ChangeOrder mapToChangeOrder(java.util.Map<String, Object> m) {
        ChangeOrder co = new ChangeOrder();
        if (m.get("id") != null) {
            co.setId(Long.valueOf(m.get("id").toString()));
        }
        if (m.get("co") != null) co.setCo(m.get("co").toString());
        if (m.get("description") != null) co.setDescription(m.get("description").toString());
        if (m.get("status") != null) co.setStatus(m.get("status").toString());
        if (m.get("amount") != null) co.setAmount(Double.valueOf(m.get("amount").toString()));
        if (m.get("ifaDate") != null && !m.get("ifaDate").toString().isEmpty()) {
            co.setIfaDate(java.time.LocalDate.parse(m.get("ifaDate").toString()));
        }
        if (m.get("ifaPer") != null) co.setIfaPer(m.get("ifaPer").toString());
        if (m.get("iffDate") != null && !m.get("iffDate").toString().isEmpty()) {
            co.setIffDate(java.time.LocalDate.parse(m.get("iffDate").toString()));
        }
        if (m.get("iffPer") != null) co.setIffPer(m.get("iffPer").toString());
        if (m.get("remarks") != null) co.setRemarks(m.get("remarks").toString());
        return co;
    }
}