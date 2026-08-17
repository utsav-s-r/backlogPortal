package com.college.backlog.controller;

import com.college.backlog.model.Department;
import com.college.backlog.repository.DepartmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/departments")
public class DepartmentController {

    @Autowired
    private DepartmentRepository departmentRepository;

    @GetMapping
    public List<Department> list() {
        return departmentRepository.findAll(Sort.by("deptName"));
    }
}
