package com.college.backlog.controller;

import com.college.backlog.model.Registration;
import com.college.backlog.model.Student;
import com.college.backlog.repository.RegistrationRepository;
import com.college.backlog.service.PdfService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The student form download is a plain RFC 6266 attachment. The SPA names the file itself (it
 * fetches a blob), so this header only matters to a browser opening the URL directly — which is
 * exactly where a malformed one goes unnoticed.
 */
@ExtendWith(MockitoExtension.class)
class StudentPdfDownloadHeaderTest {

    @Mock private RegistrationRepository registrationRepository;
    @Mock private PdfService pdfService;

    @InjectMocks private StudentController studentController;

    @Test
    void downloadIsAnAttachmentNamedAfterTheUsn() throws Exception {
        Student student = new Student();
        student.setRollNo("1MS24CS001");
        Registration reg = new Registration();
        reg.setRegId("REG-1");
        reg.setStudent(student);
        when(registrationRepository.findByRegId("REG-1")).thenReturn(Optional.of(reg));
        when(pdfService.generateRegistrationPdf(reg)).thenReturn(new byte[] {1, 2, 3});

        ResponseEntity<byte[]> res = studentController.downloadOwnPdf("REG-1",
                new UsernamePasswordAuthenticationToken("1MS24CS001", null));

        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"backlog-registration-1MS24CS001.pdf\"");
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(res.getBody()).containsExactly(1, 2, 3);
    }
}
