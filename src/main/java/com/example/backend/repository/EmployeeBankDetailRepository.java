package com.example.backend.repository;

import com.example.backend.entity.EmployeeBankDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmployeeBankDetailRepository extends JpaRepository<EmployeeBankDetail, Long> {
    Optional<EmployeeBankDetail> findByEmployeeUsername(String employeeUsername);

    java.util.List<EmployeeBankDetail> findByEmployeeUsernameIn(java.util.List<String> employeeUsernames);
}