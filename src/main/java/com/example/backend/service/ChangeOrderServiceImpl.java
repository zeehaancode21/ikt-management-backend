package com.example.backend.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.backend.Exception.ResourceNotFoundException;
import com.example.backend.entity.ChangeOrder;
import com.example.backend.repository.ChangeOrderRepository;
import com.example.backend.repository.ProjectStatusRepository;

@Service
@Transactional
public class ChangeOrderServiceImpl implements ChangeOrderService {

    @Autowired
    private ChangeOrderRepository changeOrderRepository;

    @Autowired
    private ProjectStatusRepository projectStatusRepository;

    private int highestCoNumber(List<ChangeOrder> existing) {
        return existing.stream()
            .map(ChangeOrder::getCo)
            .filter(co -> co != null && co.matches("CO-\\d+"))
            .mapToInt(co -> Integer.parseInt(co.substring(3)))
            .max()
            .orElse(0);
    }

    private String nextCoNumber(List<ChangeOrder> existing) {
        return String.format("CO-%03d", highestCoNumber(existing) + 1);
    }

    @Override
    public List<ChangeOrder> getChangeOrdersByProjectName(String projectName) {
        if (!projectStatusRepository.existsByProjectName(projectName)) {
            throw new ResourceNotFoundException("Project not found: " + projectName);
        }
        return changeOrderRepository.findByProjectNameOrderByIdAsc(projectName);
    }

    @Override
    public ChangeOrder getChangeOrderById(Long id) {
        return changeOrderRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Change order not found with id: " + id));
    }

    @Override
    public ChangeOrder createChangeOrder(String projectName, ChangeOrder changeOrder) {
        if (!projectStatusRepository.existsByProjectName(projectName)) {
            throw new ResourceNotFoundException("Project not found: " + projectName);
        }

        if (changeOrder.getCo() == null || changeOrder.getCo().isEmpty()) {
            List<ChangeOrder> existing = changeOrderRepository.findByProjectNameOrderByIdAsc(projectName);
            changeOrder.setCo(nextCoNumber(existing));
        }
        

        if (changeOrder.getStatus() == null) changeOrder.setStatus("APPROVAL PENDING");
        if (changeOrder.getAmount() == null) changeOrder.setAmount(0.0);

        changeOrder.setProjectName(projectName);
        changeOrder.setCreatedAt(LocalDateTime.now());
        changeOrder.setUpdatedAt(LocalDateTime.now());

        return changeOrderRepository.save(changeOrder);
    }

    @Override
    public ChangeOrder updateChangeOrder(Long id, ChangeOrder details) {
        ChangeOrder existing = getChangeOrderById(id);

        if (details.getCo() != null)          existing.setCo(details.getCo());
        if (details.getDescription() != null)  existing.setDescription(details.getDescription());
        if (details.getStatus() != null)       existing.setStatus(details.getStatus());
        if (details.getAmount() != null)       existing.setAmount(details.getAmount());
        if (details.getIfaDate() != null)      existing.setIfaDate(details.getIfaDate());
        if (details.getIfaPer() != null)       existing.setIfaPer(details.getIfaPer());
        if (details.getIffDate() != null)      existing.setIffDate(details.getIffDate());
        if (details.getIffPer() != null)       existing.setIffPer(details.getIffPer());
        if (details.getRemarks() != null)      existing.setRemarks(details.getRemarks());

        existing.setUpdatedAt(LocalDateTime.now());
        return changeOrderRepository.save(existing);
    }

    @Override
    public void deleteChangeOrder(Long id) {
        ChangeOrder co = getChangeOrderById(id);
        changeOrderRepository.delete(co);
    }

    @Override
    public List<ChangeOrder> createBulkChangeOrder(String projectName, List<ChangeOrder> orders) {
        if (!projectStatusRepository.existsByProjectName(projectName)) {
            throw new ResourceNotFoundException("Project not found: " + projectName);
        }

        List<ChangeOrder> existing = changeOrderRepository.findByProjectNameOrderByIdAsc(projectName);
        final int[] counter = { highestCoNumber(existing) };

        List<ChangeOrder> prepared = orders.stream().map(order -> {
            if (order.getCo() == null || order.getCo().isEmpty()) {
                counter[0]++;
                order.setCo(String.format("CO-%03d", counter[0]));
            }
            if (order.getStatus() == null) order.setStatus("APPROVAL PENDING");
            if (order.getAmount() == null) order.setAmount(0.0);
            order.setProjectName(projectName);
            order.setCreatedAt(LocalDateTime.now());
            order.setUpdatedAt(LocalDateTime.now());
            return order;
        }).collect(Collectors.toList());

        return changeOrderRepository.saveAll(prepared);
    }
}