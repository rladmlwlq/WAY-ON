package com.wayon.seed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * 공공데이터 포털에서 받은 파일 읽기.
 * 판매순위 파일은 이름이 .zip인데 실제로는 gzip이고, 안의 CSV는 CP949다 (2026-11-05 확인).
 */
final class SourceFiles {

	private static final Charset CP949 = Charset.forName("MS949");

	private SourceFiles() {
	}

	static String readText(Path file) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		if (bytes.length > 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B) {
			try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
				bytes = in.readAllBytes();
			}
		}
		return decode(bytes);
	}

	/** UTF-8로 깨짐 없이 읽히면 UTF-8, 아니면 CP949 */
	static String decode(byte[] bytes) {
		try {
			String s = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes)).toString();
			return s.startsWith("﻿") ? s.substring(1) : s;
		} catch (CharacterCodingException e) {
			return new String(bytes, CP949);
		}
	}
}
