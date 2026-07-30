package com.example.backend.service;

import java.util.List;

import com.example.backend.entity.ChangeOrder;

public interface ChangeOrderService {
    List<ChangeOrder> getChangeOrdersByProjectName(String projectName);
    ChangeOrder getChangeOrderById(Long id);
    ChangeOrder createChangeOrder(String projectName, ChangeOrder changeOrder);
    ChangeOrder updateChangeOrder(Long id, ChangeOrder changeOrderDetails);
    void deleteChangeOrder(Long id);
    List<ChangeOrder> createBulkChangeOrder(String projectName, List<ChangeOrder> projectStatusList);
}