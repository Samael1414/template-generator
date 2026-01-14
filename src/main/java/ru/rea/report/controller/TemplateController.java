package ru.rea.report.controller;

import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.rea.report.core.TemplateType;
import ru.rea.report.service.TemplateConvertService;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/templates")
public class TemplateController {

    private final TemplateConvertService convertService;

    public TemplateController(TemplateConvertService convertService) {
        this.convertService = convertService;
    }

    @PostMapping(
            value = "/convert",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<byte[]> convert(@RequestPart("file") @NotNull MultipartFile file) {
        String originalName = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "template";
        TemplateType type = TemplateType.fromFilename(originalName);

        byte[] rpt = convertService.convertToRptdesign(file, type);

        String outName = TemplateType.stripExtension(originalName) + ".rptdesign";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + outName + "\"; filename*=UTF-8''" + urlEncode(outName))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(rpt);
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
    }
}
