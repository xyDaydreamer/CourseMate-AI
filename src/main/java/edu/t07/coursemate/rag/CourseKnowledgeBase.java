package edu.t07.coursemate.rag;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** One local cache per course folder. Only refresh parses changed files; querying uses an in-memory index. */
public final class CourseKnowledgeBase {
    private static final int CACHE_VERSION = 1;
    private final Path directory;
    private final Path cache;
    private final PdfCourseReader reader;
    private List<FileIndex> documents = List.of();
    private KeywordIndex index = new KeywordIndex(List.of());
    private boolean loaded;
    private boolean indexed;
    private Report report = new Report(List.of(), List.of(), 0);

    public CourseKnowledgeBase(Path directory) {
        this(directory, directory.resolveSibling(".coursemate").resolve("index.json"), new PdfCourseReader());
    }

    public CourseKnowledgeBase(Path directory, Path cache, PdfCourseReader reader) {
        this.directory = directory.toAbsolutePath().normalize();
        this.cache = cache;
        this.reader = reader;
    }

    public static Path locate(String projectBasePath) {
        String environment = System.getenv("COURSEMATE_COURSE_FILES");
        if (environment != null && !environment.isBlank()) return Path.of(environment);
        String property = System.getProperty("coursemate.courseDir");
        if (property != null && !property.isBlank()) return Path.of(property);
        return Path.of(projectBasePath == null ? "." : projectBasePath).resolve("course_files");
    }

    public static boolean isLecture(String fileName) {
        return fileName.matches("(?i)^Lec-[0-5](?:[.-].*)?\\.pdf$");
    }

    public Path directory() { return directory; }

    public synchronized Report refresh(boolean force) {
        List<String> warnings = new ArrayList<>();
        if (!loaded) {
            try {
                Cache stored = new Gson().fromJson(Files.readString(cache), Cache.class);
                if (stored != null && stored.version() == CACHE_VERSION && directory.toString().equals(stored.directory())) {
                    documents = List.copyOf(stored.documents());
                    // Validate cached values before trusting them as retrieval sources.
                    for (FileIndex file : documents) {
                        if (!isLecture(file.name()) || file.chunks() == null || file.problem() == null
                                || file.pages() < 0 || file.textPages() < 0 || file.textPages() > file.pages()) throw new IllegalArgumentException();
                        for (CourseChunk chunk : file.chunks()) {
                            if (!file.name().equals(chunk.fileName()) || chunk.page() > file.pages()) throw new IllegalArgumentException();
                        }
                    }
                }
            } catch (NoSuchFileException ignored) {
                // First run is normal.
            } catch (IOException | RuntimeException ignored) {
                documents = List.of();
                warnings.add("本地索引缓存无法读取，将重新建立。");
            }
            loaded = true;
        }
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile).filter(file -> isLecture(file.getFileName().toString()))
                    .sorted().toList();
        } catch (IOException | SecurityException ignored) {
            documents = List.of();
            index = new KeywordIndex(List.of());
            indexed = false;
            return report = new Report(List.of(), List.of("无法读取课程目录：" + directory + "。请检查路径与权限。"), 0);
        }
        Map<String, FileIndex> old = new HashMap<>();
        documents.forEach(document -> old.put(document.name(), document));
        List<FileIndex> next = new ArrayList<>();
        for (Path file : files) {
            if (Thread.currentThread().isInterrupted()) return report;
            String name = file.getFileName().toString();
            long size = -1, modified = -1;
            try {
                size = Files.size(file);
                modified = Files.getLastModifiedTime(file).toMillis();
                FileIndex prior = old.get(name);
                if (!force && prior != null && prior.size() == size && prior.modified() == modified) {
                    next.add(prior);
                    continue;
                }
                PdfCourseReader.Document parsed = reader.read(file);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                next.add(new FileIndex(name, size, modified, hash, parsed.pages(), parsed.textPages(), parsed.chunks(), ""));
            } catch (IOException | RuntimeException | NoSuchAlgorithmException ignored) {
                // A changed/unreadable file must not keep old chunks or silently claim success.
                next.add(new FileIndex(name, size, modified, "", 0, 0, List.of(),
                        "无法读取或提取文字（损坏、加密、无权限或扫描件）；未建立该文件的索引。"));
            }
        }
        boolean changed = !next.equals(documents);
        if (changed || force || !indexed || !Files.exists(cache)) {
            documents = List.copyOf(next);
            index = new KeywordIndex(documents.stream().flatMap(file -> file.chunks().stream()).toList());
            indexed = true;
            try {
                Files.createDirectories(cache.toAbsolutePath().getParent());
                Path temporary = Files.createTempFile(cache.toAbsolutePath().getParent(), "index-", ".tmp");
                try {
                    Files.writeString(temporary, new Gson().toJson(new Cache(CACHE_VERSION, directory.toString(), documents)), StandardCharsets.UTF_8);
                    try {
                        Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException ignored) {
                        Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            } catch (IOException | SecurityException ignored) {
                warnings.add("无法保存本地缓存；本次仍可检索，下次启动可能需要重建。");
            }
        }
        if (files.isEmpty()) warnings.add("没有发现 Lec-0 至 Lec-5 讲义 PDF，未建立课程知识库。");
        for (FileIndex file : documents) {
            if (!file.problem().isEmpty()) warnings.add(file.name() + "：" + file.problem());
            else if (file.textPages() < file.pages()) warnings.add(file.name() + "：跳过 " + (file.pages() - file.textPages()) + " 个无文字页（不做 OCR）。");
        }
        return report = new Report(documents, List.copyOf(warnings), documents.stream().mapToInt(file -> file.chunks().size()).sum());
    }

    public synchronized List<CourseChunk> search(String question) { return index.search(question); }

    public record FileIndex(String name, long size, long modified, String sha256, int pages,
                            int textPages, List<CourseChunk> chunks, String problem) { }
    private record Cache(int version, String directory, List<FileIndex> documents) { }
    public record Report(List<FileIndex> documents, List<String> warnings, int chunkCount) {
        public String summary() {
            long success = documents.stream().filter(file -> !file.chunks().isEmpty()).count();
            StringBuilder text = new StringBuilder("知识库：" + success + " 份讲义，" + chunkCount + " 个文本块。\n");
            for (FileIndex file : documents) if (!file.chunks().isEmpty()) {
                text.append(file.name()).append("：").append(file.textPages()).append("/").append(file.pages()).append(" 页可提取文字\n");
            }
            for (String warning : warnings) text.append("[知识库提示] ").append(warning).append('\n');
            return text.toString();
        }
    }
}
