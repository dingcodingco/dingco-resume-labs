package com.dingco.labs.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 실험 결과를 evidence/{lab}-{시각}.json 으로 남긴다. 외부 라이브러리 없이 직렬화한다. */
public class Evidence {

    private final String lab;
    private final Map<String, Object> root = new LinkedHashMap<>();
    private final List<Map<String, Object>> rows = new ArrayList<>();

    public Evidence(String lab) {
        this.lab = lab;
        root.put("lab", lab);
        root.put("startedAt", LocalDateTime.now().toString());
        root.put("java", System.getProperty("java.version"));
        root.put("rows", rows);
    }

    public Evidence meta(String key, Object value) {
        root.put(key, value);
        return this;
    }

    public void row(Map<String, Object> row) {
        rows.add(row);
        System.out.println("[" + lab + "] " + row);
    }

    public Path save() {
        root.put("finishedAt", LocalDateTime.now().toString());
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path path = Path.of("evidence", lab + "-" + stamp + ".json");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, toJson(root, 0));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        System.out.println("[" + lab + "] evidence saved: " + path);
        return path;
    }

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String toJson(Object v, int depth) {
        String pad = "  ".repeat(depth + 1);
        String end = "  ".repeat(depth);
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder("{\n");
            var it = m.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                sb.append(pad).append(quote(String.valueOf(e.getKey()))).append(": ").append(toJson(e.getValue(), depth + 1));
                sb.append(it.hasNext() ? ",\n" : "\n");
            }
            return sb.append(end).append("}").toString();
        }
        if (v instanceof Collection<?> c) {
            if (c.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder("[\n");
            var it = c.iterator();
            while (it.hasNext()) {
                sb.append(pad).append(toJson(it.next(), depth + 1));
                sb.append(it.hasNext() ? ",\n" : "\n");
            }
            return sb.append(end).append("]").toString();
        }
        return quote(v.toString());
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }
}
