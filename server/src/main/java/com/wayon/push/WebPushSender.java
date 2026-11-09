package com.wayon.push;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Web Push 보내기 (출발해요, app-api.md 7-3). 라이브러리 없이 JDK만 쓴다.
 * - 본문 암호화: RFC 8291 (aes128gcm, 레코드 1개)
 * - 서버 인증: RFC 8292 VAPID (ES256 JWT). 키는 server/.env의 VAPID_PUBLIC_KEY·VAPID_PRIVATE_KEY (team-setup.md 4-6)
 * 키가 없으면 보내지 않고 건너뛴다 (WebSocket으로 받는 동승자는 그대로 받음).
 */
@Component
public class WebPushSender {

	/** 푸시 서버 응답. 404·410이면 구독이 끝난 것이라 지운다 (erd.md 출발 알림 규칙) */
	public enum Result { SENT, GONE, FAILED, SKIPPED }

	public record Subscription(String endpoint, String p256dh, String auth) {
	}

	private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);
	private static final Base64.Decoder B64 = Base64.getUrlDecoder();
	private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int RECORD_SIZE = 4096;
	/** 출발 알림은 30분 뒤면 의미가 없다 */
	private static final Duration TTL = Duration.ofMinutes(30);

	private final String publicKey;
	private final ECPrivateKey privateKey;
	private final String subject;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	public WebPushSender(@Value("${VAPID_PUBLIC_KEY:}") String publicKey,
			@Value("${VAPID_PRIVATE_KEY:}") String privateKey,
			@Value("${VAPID_SUBJECT:mailto:wayon-dev@invalid}") String subject) {
		this.publicKey = publicKey;
		this.subject = subject;
		this.privateKey = publicKey.isBlank() || privateKey.isBlank() ? null : privateKey(privateKey);
	}

	public boolean enabled() {
		return privateKey != null;
	}

	/** 비동기로 보낸다. 결과는 구독 정리에만 쓴다 */
	public CompletableFuture<Result> send(Subscription to, String json) {
		if (!enabled()) {
			return CompletableFuture.completedFuture(Result.SKIPPED);
		}
		HttpRequest request;
		try {
			URI endpoint = URI.create(to.endpoint());
			request = HttpRequest.newBuilder(endpoint)
					.timeout(Duration.ofSeconds(10))
					.header("TTL", Long.toString(TTL.toSeconds()))
					.header("Urgency", "high")
					.header("Content-Encoding", "aes128gcm")
					.header("Content-Type", "application/octet-stream")
					.header("Authorization", "vapid t=" + vapidJwt(endpoint) + ", k=" + publicKey)
					.POST(HttpRequest.BodyPublishers.ofByteArray(
							encrypt(json.getBytes(StandardCharsets.UTF_8), B64.decode(to.p256dh()), B64.decode(to.auth()))))
					.build();
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			log.warn("web push build failed {}", e.toString());
			return CompletableFuture.completedFuture(Result.FAILED);
		}
		return http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).handle((res, e) -> {
			if (e != null) {
				log.warn("web push failed {}", e.toString());
				return Result.FAILED;
			}
			int code = res.statusCode();
			if (code == 404 || code == 410) {
				return Result.GONE;
			}
			if (code >= 300) {
				log.warn("web push status {}", code);
				return Result.FAILED;
			}
			return Result.SENT;
		});
	}

	// ---- RFC 8291 ----

	/** 받는 브라우저의 공개 키(p256dh)와 auth로 본문을 암호화한다. 헤더(salt, rs, keyid) + 암호문 */
	static byte[] encrypt(byte[] plain, byte[] uaPublic, byte[] authSecret) throws GeneralSecurityException {
		ECParameterSpec p256 = p256();
		KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
		gen.initialize(p256);
		KeyPair as = gen.generateKeyPair();
		byte[] asPublic = encodePoint((ECPublicKey) as.getPublic());

		KeyAgreement ecdh = KeyAgreement.getInstance("ECDH");
		ecdh.init(as.getPrivate());
		ecdh.doPhase(publicKey(uaPublic, p256), true);
		byte[] ecdhSecret = ecdh.generateSecret();

		byte[] salt = new byte[16];
		RANDOM.nextBytes(salt);
		byte[][] keys = contentKeys(ecdhSecret, authSecret, uaPublic, asPublic, salt);

		byte[] record = Arrays.copyOf(plain, plain.length + 1);
		record[plain.length] = 0x02; // 마지막 레코드 구분자, 채움 없음
		Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
		aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys[0], "AES"), new GCMParameterSpec(128, keys[1]));
		byte[] cipher = aes.doFinal(record);

		ByteBuffer out = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + cipher.length);
		out.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic).put(cipher);
		return out.array();
	}

	/** [CEK(16), NONCE(12)]. 받는 쪽은 같은 계산을 ecdh(ua 개인 키, as 공개 키)로 한다 */
	static byte[][] contentKeys(byte[] ecdhSecret, byte[] authSecret, byte[] uaPublic, byte[] asPublic, byte[] salt)
			throws GeneralSecurityException {
		ByteArrayOutputStream keyInfo = new ByteArrayOutputStream();
		keyInfo.writeBytes("WebPush: info\0".getBytes(StandardCharsets.US_ASCII));
		keyInfo.writeBytes(uaPublic);
		keyInfo.writeBytes(asPublic);
		byte[] ikm = expand(hmac(authSecret, ecdhSecret), keyInfo.toByteArray(), 32);
		byte[] prk = hmac(salt, ikm);
		byte[] cek = expand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
		byte[] nonce = expand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
		return new byte[][] { cek, nonce };
	}

	/** HKDF-Expand, 32바이트 이하라 한 블록 */
	private static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
		byte[] input = Arrays.copyOf(info, info.length + 1);
		input[info.length] = 0x01;
		return Arrays.copyOf(hmac(prk, input), length);
	}

	private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		return mac.doFinal(data);
	}

	// ---- RFC 8292 VAPID ----

	private String vapidJwt(URI endpoint) throws GeneralSecurityException {
		String audience = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
		long exp = Instant.now().plus(Duration.ofHours(12)).getEpochSecond();
		String header = b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}");
		String claims = b64("{\"aud\":\"" + audience + "\",\"exp\":" + exp + ",\"sub\":\"" + subject + "\"}");
		Signature es256 = Signature.getInstance("SHA256withECDSAinP1363Format"); // JWT는 R||S 64바이트
		es256.initSign(privateKey);
		es256.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
		return header + "." + claims + "." + B64E.encodeToString(es256.sign());
	}

	private static String b64(String json) {
		return B64E.encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	// ---- P-256 키 ----

	static ECParameterSpec p256() throws GeneralSecurityException {
		AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
		params.init(new ECGenParameterSpec("secp256r1"));
		return params.getParameterSpec(ECParameterSpec.class);
	}

	/** 비압축 점 0x04||X||Y (65바이트) */
	static ECPublicKey publicKey(byte[] point, ECParameterSpec p256) throws GeneralSecurityException {
		if (point.length != 65 || point[0] != 0x04) {
			throw new GeneralSecurityException("bad public key");
		}
		ECPoint w = new ECPoint(new BigInteger(1, Arrays.copyOfRange(point, 1, 33)),
				new BigInteger(1, Arrays.copyOfRange(point, 33, 65)));
		return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, p256));
	}

	static byte[] encodePoint(ECPublicKey key) {
		byte[] out = new byte[65];
		out[0] = 0x04;
		copyFixed(key.getW().getAffineX(), out, 1);
		copyFixed(key.getW().getAffineY(), out, 33);
		return out;
	}

	private static void copyFixed(BigInteger value, byte[] out, int offset) {
		byte[] raw = value.toByteArray(); // 앞에 0x00이 붙거나 32바이트보다 짧을 수 있다
		int len = Math.min(raw.length, 32);
		System.arraycopy(raw, raw.length - len, out, offset + 32 - len, len);
	}

	private static ECPrivateKey privateKey(String d) {
		try {
			return (ECPrivateKey) KeyFactory.getInstance("EC")
					.generatePrivate(new ECPrivateKeySpec(new BigInteger(1, B64.decode(d)), p256()));
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IllegalStateException("VAPID_PRIVATE_KEY 형식이 맞지 않아요", e);
		}
	}

	/** 키 만들기: VAPID_PUBLIC_KEY=…, VAPID_PRIVATE_KEY=… 두 줄을 찍는다 (team-setup.md 4-6) */
	public static void main(String[] args) throws GeneralSecurityException {
		KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
		gen.initialize(p256());
		KeyPair pair = gen.generateKeyPair();
		byte[] d = new byte[32];
		copyFixed(((ECPrivateKey) pair.getPrivate()).getS(), d, 0);
		System.out.println("VAPID_PUBLIC_KEY=" + B64E.encodeToString(encodePoint((ECPublicKey) pair.getPublic())));
		System.out.println("VAPID_PRIVATE_KEY=" + B64E.encodeToString(d));
	}
}
