package ru.rea.report.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.rea.report.birt.BirtDesignBuilder;
import ru.rea.report.core.TemplateType;
import ru.rea.report.exception.BadTemplateException;
import ru.rea.report.exception.TemplateProcessingException;
import ru.rea.report.ir.TemplateDocumentIR;
import ru.rea.report.odf.OdfTemplateReader;
import ru.rea.report.tags.TagExtractor;
import ru.rea.report.tags.TagRegistry;
import ru.rea.report.utils.TableNormalizer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
@RequiredArgsConstructor
public class TemplateConvertService {

    private final OdfTemplateReader reader;
    private final TagExtractor tagExtractor;
    private final BirtDesignBuilder birtDesignBuilder;

    public byte[] convertToRptdesign(MultipartFile file, TemplateType type) {
        try (InputStream in = file.getInputStream()) {
            System.out.println("[TPLGEN] multipart type=" + type);
            return convert(in, type);
        } catch (IllegalArgumentException e) {
            throw new BadTemplateException(e.getMessage(), e);
        } catch (BadTemplateException e) {
            throw e;
        } catch (Exception e) {
            throw new TemplateProcessingException("Failed to convert template to rptdesign", e);
        }
    }

    public byte[] convertToRptdesign(Path inputPath) {
        TemplateType type = detectType(inputPath);
        System.out.println("[TPLGEN] input=" + inputPath + " type=" + type);

        try (InputStream in = Files.newInputStream(inputPath)) {
            return convert(in, type);
        } catch (BadTemplateException e) {
            throw e;
        } catch (Exception e) {
            throw new TemplateProcessingException("Failed to convert template", e);
        }
    }

    private byte[] convert(InputStream in, TemplateType type) throws Exception {
        long t0 = System.nanoTime();

        System.out.println("[TPLGEN] step=read:start");
        long t1 = System.nanoTime();
        TemplateDocumentIR ir = reader.read(in, type);
        long t2 = System.nanoTime();
        System.out.println("[TPLGEN] step=read:done ms=" + ms(t2 - t1));

        System.out.println("[TPLGEN] step=normalize:start");
        long t3 = System.nanoTime();
        TableNormalizer.trimAllTables(ir);
        long t4 = System.nanoTime();
        System.out.println("[TPLGEN] step=normalize:done ms=" + ms(t4 - t3));

        System.out.println("[TPLGEN] step=tags:start");
        long t5 = System.nanoTime();
        TagRegistry tags = tagExtractor.extract(ir);
        long t6 = System.nanoTime();
        System.out.println("[TPLGEN] step=tags:done ms=" + ms(t6 - t5));

        System.out.println("[TPLGEN] step=build:start");
        long t7 = System.nanoTime();
        ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
        birtDesignBuilder.build(ir, tags, out);
        long t8 = System.nanoTime();
        System.out.println("[TPLGEN] step=build:done ms=" + ms(t8 - t7));

        System.out.println("[TPLGEN] total ms=" + ms(t8 - t0));
        return out.toByteArray();
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000L;
    }

    private static TemplateType detectType(Path inputPath) {
        String fn = inputPath.getFileName().toString().toLowerCase();
        if (fn.endsWith(".odt")) return TemplateType.ODT;
        if (fn.endsWith(".ods")) return TemplateType.ODS;
        throw new BadTemplateException("Unsupported file type: " + fn);
    }
}
