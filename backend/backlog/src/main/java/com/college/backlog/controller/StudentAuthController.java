package com.college.backlog.controller;

import com.college.backlog.model.Student;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.security.JwtService;
import com.college.backlog.security.SessionCookieService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/student/auth")
public class StudentAuthController {

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private SessionCookieService sessionCookieService;

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody Map<String, String> body,
                                     HttpServletResponse response) {

        String rollNo = body.getOrDefault("rollNo", "").trim();
        String dob = body.getOrDefault("dateOfBirth", "").trim();

        if (rollNo.isEmpty() || dob.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "USN and date of birth are required");
        }

        LocalDate dateOfBirth;
        try {
            dateOfBirth = LocalDate.parse(dob); // expects ISO yyyy-MM-dd
        } catch (DateTimeParseException e) {
            throw invalidCredentials();
        }

        Student student = studentRepository.findByRollNo(rollNo).orElse(null);
        if (student == null
                || student.getDateOfBirth() == null
                || !student.getDateOfBirth().equals(dateOfBirth)) {
            throw invalidCredentials();
        }

        String token = jwtService.generateToken(student.getRollNo(), "STUDENT");
        sessionCookieService.write(response, SessionCookieService.STUDENT_COOKIE, token);

        Map<String, String> body2 = new HashMap<>();
        body2.put("message", "Login success");
        body2.put("expiresIn", String.valueOf(jwtService.secondsUntilExpiry(token)));
        body2.put("rollNo", student.getRollNo());
        body2.put("name", student.getName());
        return body2;
    }

    /** Log out: expire the student session cookie. */
    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletResponse response) {
        sessionCookieService.clear(response, SessionCookieService.STUDENT_COOKIE);
        Map<String, String> resp = new HashMap<>();
        resp.put("message", "Logged out");
        return resp;
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid USN or date of birth");
    }
}
