package kr.co.teambrain.marvelrun.user.common.attachment.storage.upload;

import kr.co.teambrain.marvelrun.user.common.attachment.storage.exception.AttachmentFileValidationException;
import kr.co.teambrain.marvelrun.user.common.attachment.storage.AttachmentValidatedFile;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class AttachmentFileValidator {

    private static final int HEADER_LENGTH = 16;

    private final AttachmentUploadProperties properties;


    public List<AttachmentValidatedFile> validate(
            List<MultipartFile> files
    ) {

        if (files == null || files.isEmpty()) {
            return List.of();
        }

        validateCount(files);
        validateTotalSize(files);

        List<AttachmentValidatedFile> result =
                new ArrayList<>(files.size());

        for (MultipartFile file : files) {

            result.add(
                    validateFile(file)
            );
        }

        return List.copyOf(result);
    }


    private void validateCount(
            List<MultipartFile> files
    ) {

        if (files.size()
                > properties.getMaxCount()) {

            throw new AttachmentFileValidationException(
                    "Too many attachment files. max="
                            + properties.getMaxCount()
            );
        }
    }


    private void validateTotalSize(
            List<MultipartFile> files
    ) {

        long totalSize = 0L;

        for (MultipartFile file : files) {

            totalSize =
                    Math.addExact(
                            totalSize,
                            file.getSize()
                    );
        }

        if (totalSize
                > properties
                .getMaxTotalSize()
                .toBytes()) {

            throw new AttachmentFileValidationException(
                    "Total attachment size exceeded."
            );
        }
    }


    private AttachmentValidatedFile validateFile(
            MultipartFile file
    ) {

        validateNotEmpty(file);
        validateFileSize(file);

        AttachmentFileType detectedType =
                detectFileType(file);

        validateAllowedType(
                detectedType
        );

        /*
         * Client가 보낸 Content-Type과
         * 실제 binary signature가 일치하는지도 확인한다.
         */
        validateDeclaredContentType(
                file,
                detectedType
        );

        validateImageStructure(
                file,
                detectedType
        );

        String originalName =
                sanitizeOriginalName(
                        file.getOriginalFilename()
                );

        return new AttachmentValidatedFile(
                file,
                originalName,
                detectedType,
                file.getSize()
        );
    }


    private void validateNotEmpty(
            MultipartFile file
    ) {

        if (file == null
                || file.isEmpty()
                || file.getSize() <= 0) {

            throw new AttachmentFileValidationException(
                    "Empty attachment file."
            );
        }
    }


    private void validateFileSize(
            MultipartFile file
    ) {

        if (file.getSize()
                > properties
                .getMaxFileSize()
                .toBytes()) {

            throw new AttachmentFileValidationException(
                    "Attachment file size exceeded."
            );
        }
    }


    private AttachmentFileType detectFileType(
            MultipartFile file
    ) {

        try (InputStream inputStream =
                     file.getInputStream()) {

            byte[] header =
                    inputStream.readNBytes(
                            HEADER_LENGTH
                    );

            return AttachmentFileType.detect(
                    header
            );

        } catch (IOException e) {

            throw new AttachmentFileValidationException(
                    "Failed to read attachment file.",
                    e
            );
        }
    }


    private void validateAllowedType(
            AttachmentFileType type
    ) {

        if (!properties
                .getAllowedTypes()
                .contains(type)) {

            throw new AttachmentFileValidationException(
                    "Attachment file type is not allowed. type="
                            + type
            );
        }
    }


    private void validateDeclaredContentType(
            MultipartFile file,
            AttachmentFileType actualType
    ) {

        String declaredContentType =
                file.getContentType();

        if (declaredContentType == null
                || declaredContentType.isBlank()) {

            throw new AttachmentFileValidationException(
                    "Attachment Content-Type is missing."
            );
        }

        if (!actualType
                .getContentType()
                .equalsIgnoreCase(
                        declaredContentType
                )) {

            throw new AttachmentFileValidationException(
                    "Attachment Content-Type mismatch."
            );
        }
    }


    private void validateImageStructure(
            MultipartFile file,
            AttachmentFileType detectedType
    ) {

        try (
                InputStream inputStream =
                        file.getInputStream();

                ImageInputStream imageInputStream =
                        ImageIO.createImageInputStream(
                                inputStream
                        )
        ) {

            if (imageInputStream == null) {

                throw new AttachmentFileValidationException(
                        "Invalid image."
                );
            }

            Iterator<ImageReader> readers =
                    ImageIO.getImageReaders(
                            imageInputStream
                    );

            if (!readers.hasNext()) {

                throw new AttachmentFileValidationException(
                        "Unsupported or invalid image."
                );
            }

            ImageReader reader =
                    readers.next();

            try {

                reader.setInput(
                        imageInputStream,
                        true,
                        true
                );

                validateImageFormat(
                        reader,
                        detectedType
                );

                int width =
                        reader.getWidth(0);

                int height =
                        reader.getHeight(0);

                validatePixelCount(
                        width,
                        height
                );

            } finally {

                reader.dispose();
            }

        } catch (IOException e) {

            throw new AttachmentFileValidationException(
                    "Invalid image structure.",
                    e
            );
        }
    }


    private void validateImageFormat(
            ImageReader reader,
            AttachmentFileType expectedType
    ) throws IOException {

        String format =
                reader
                        .getFormatName()
                        .toUpperCase(Locale.ROOT);

        boolean matches =
                switch (expectedType) {

                    case JPEG ->
                            format.equals("JPEG")
                                    || format.equals("JPG");

                    case PNG ->
                            format.equals("PNG");
                };

        if (!matches) {

            throw new AttachmentFileValidationException(
                    "Image format mismatch."
            );
        }
    }


    private void validatePixelCount(
            int width,
            int height
    ) {

        if (width <= 0 || height <= 0) {

            throw new AttachmentFileValidationException(
                    "Invalid image dimensions."
            );
        }

        long pixels =
                (long) width * height;

        if (pixels
                > properties.getMaxPixels()) {

            throw new AttachmentFileValidationException(
                    "Image resolution exceeded."
            );
        }
    }


    private String sanitizeOriginalName(
            String originalName
    ) {

        if (originalName == null
                || originalName.isBlank()) {

            return "image";
        }

        /*
         * Windows / Unix 양쪽 path component 제거.
         * 실제 저장 path에는 사용하지 않고
         * 사용자 표시용 metadata로만 사용한다.
         */
        String normalized =
                originalName
                        .replace('\\', '/');

        int lastSlash =
                normalized.lastIndexOf('/');

        if (lastSlash >= 0) {

            normalized =
                    normalized.substring(
                            lastSlash + 1
                    );
        }

        /*
         * Header/log 등으로 값이 재사용될 가능성을 고려하여
         * 제어문자 제거.
         */
        normalized =
                normalized
                        .replace("\r", "")
                        .replace("\n", "")
                        .replace("\0", "");

        if (normalized.isBlank()) {
            return "image";
        }

        if (normalized.length() > 255) {

            normalized =
                    normalized.substring(
                            0,
                            255
                    );
        }

        return normalized;
    }

    private void validateFinalCount(
            int existingCount,
            int deleteCount,
            int newCount
    ) {

        int finalCount =
                existingCount
                        - deleteCount
                        + newCount;


        if (finalCount < 0
                || finalCount
                > properties
                .getMaxCount()) {

            throw new CustomException(
                    ErrorCode.FILE_LENGTH_EXCEEDED
            );
        }
    }
}