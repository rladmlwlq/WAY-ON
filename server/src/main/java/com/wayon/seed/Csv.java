package com.wayon.seed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 적재 결과를 git으로 나누기 위한 CSV (UTF-8, 첫 줄은 헤더).
 * 쉼표·따옴표·줄바꿈이 있는 값은 따옴표로 감싸고, 빈 값은 null로 읽는다.
 */
public final class Csv {

	private Csv() {
	}

	public static void write(Path file, List<String> header, List<List<Object>> rows) throws IOException {
		Files.createDirectories(file.getParent());
		StringBuilder sb = new StringBuilder();
		sb.append(line(new ArrayList<>(header))).append('\n');
		for (List<Object> row : rows) {
			sb.append(line(row)).append('\n');
		}
		Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
	}

	/** 헤더를 뺀 행들. 값은 문자열이며 빈 칸은 null */
	public static List<String[]> read(Path file) throws IOException {
		String text = Files.readString(file, StandardCharsets.UTF_8);
		List<String[]> rows = new ArrayList<>();
		List<String> cur = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean quoted = false;
		boolean wasQuoted = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (quoted) {
				if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
					cell.append('"');
					i++;
				} else if (c == '"') {
					quoted = false;
				} else {
					cell.append(c);
				}
			} else if (c == '"') {
				quoted = true;
				wasQuoted = true;
			} else if (c == ',') {
				cur.add(value(cell, wasQuoted));
				cell.setLength(0);
				wasQuoted = false;
			} else if (c == '\n' || c == '\r') {
				if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
					i++;
				}
				cur.add(value(cell, wasQuoted));
				cell.setLength(0);
				wasQuoted = false;
				rows.add(cur.toArray(String[]::new));
				cur = new ArrayList<>();
			} else {
				cell.append(c);
			}
		}
		if (cell.length() > 0 || !cur.isEmpty()) {
			cur.add(value(cell, wasQuoted));
			rows.add(cur.toArray(String[]::new));
		}
		return rows.isEmpty() ? rows : rows.subList(1, rows.size());
	}

	private static String value(StringBuilder cell, boolean wasQuoted) {
		String v = cell.toString();
		return v.isEmpty() && !wasQuoted ? null : v;
	}

	private static String line(List<Object> values) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			Object v = values.get(i);
			if (v == null) {
				continue;
			}
			String s = v.toString();
			if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") || s.isEmpty()) {
				sb.append('"').append(s.replace("\"", "\"\"")).append('"');
			} else {
				sb.append(s);
			}
		}
		return sb.toString();
	}
}
