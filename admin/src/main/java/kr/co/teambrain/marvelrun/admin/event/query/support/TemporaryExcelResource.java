package kr.co.teambrain.marvelrun.admin.event.query.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.AbstractResource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** 완성된 임시 엑셀만 응답에 제공하고 전송 종료·실패 시 파일을 제거한다. */
public final class TemporaryExcelResource extends AbstractResource implements AutoCloseable {
    private static final String ATTRIBUTE = TemporaryExcelResource.class.getName();
    private final Path path;
    private final String filename;
    private final long size;

    /** 파일 크기는 응답 변환기가 스트림을 미리 열지 않도록 생성 시 보관한다. */
    public TemporaryExcelResource(Path path, String filename) throws IOException {
        this.path = path; this.filename = filename; this.size = Files.size(path);
    }

    /** 변환기 진입 전 실패도 요청 종료 시 정리할 수 있도록 등록한다. */
    public void attachTo(HttpServletRequest request) { request.setAttribute(ATTRIBUTE,this); }

    /** 응답용 설명에는 로컬 파일 위치를 노출하지 않는다. */
    @Override public String getDescription() { return "배송 명단 임시 엑셀"; }

    /** 다운로드 이름을 반환한다. */
    @Override public String getFilename() { return filename; }

    /** 파일을 다시 읽지 않고 확정된 크기를 반환한다. */
    @Override public long contentLength() { return size; }

    /** 응답 변환기의 존재 확인·범위 전송에 재사용할 수 있도록 요청 종료까지 파일을 유지한다. */
    @Override public InputStream getInputStream() throws IOException {
        return Files.newInputStream(path);
    }

    /** 명시적인 자원 종료와 요청 종료에서 중복 호출해도 안전하게 제거한다. */
    @Override public void close() throws IOException { Files.deleteIfExists(path); }

    /** 응답 스트림을 열기 전 오류가 나더라도 요청에 귀속된 파일을 정리한다. */
    @Component
    public static class CleanupFilter extends OncePerRequestFilter {
        /** 동기식 다운로드 요청의 전체 응답 변환 이후 정리한다. */
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            try { chain.doFilter(request,response); }
            finally {
                Object resource = request.getAttribute(ATTRIBUTE);
                if (resource instanceof TemporaryExcelResource temporary) { temporary.close(); }
            }
        }
    }
}
