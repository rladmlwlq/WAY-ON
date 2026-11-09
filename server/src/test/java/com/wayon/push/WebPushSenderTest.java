package com.wayon.push;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

/** 브라우저(받는 쪽)가 하는 복호화를 그대로 해서 RFC 8291 암호화를 확인한다 */
class WebPushSenderTest {

	@Test
	void 받는_쪽_키로_풀면_원래_본문이_나온다() throws Exception {
		KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
		gen.initialize(WebPushSender.p256());
		KeyPair ua = gen.generateKeyPair();
		byte[] uaPublic = WebPushSender.encodePoint((ECPublicKey) ua.getPublic());
		byte[] auth = new byte[16];
		new SecureRandom().nextBytes(auth);
		String json = "{\"kind\":\"DEPARTURE_CALLED\",\"title\":\"곧 출발해요\"}";

		byte[] body = WebPushSender.encrypt(json.getBytes(StandardCharsets.UTF_8), uaPublic, auth);

		ByteBuffer in = ByteBuffer.wrap(body);
		byte[] salt = new byte[16];
		in.get(salt);
		assertEquals(4096, in.getInt());
		byte[] asPublic = new byte[in.get()];
		in.get(asPublic);
		byte[] cipher = new byte[in.remaining()];
		in.get(cipher);

		KeyAgreement ecdh = KeyAgreement.getInstance("ECDH");
		ecdh.init(ua.getPrivate());
		ecdh.doPhase(WebPushSender.publicKey(asPublic, WebPushSender.p256()), true);
		byte[][] keys = WebPushSender.contentKeys(ecdh.generateSecret(), auth, uaPublic, asPublic, salt);
		Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
		aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keys[0], "AES"), new GCMParameterSpec(128, keys[1]));
		byte[] record = aes.doFinal(cipher);

		assertEquals(0x02, record[record.length - 1]);
		assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), Arrays.copyOf(record, record.length - 1));
	}

	@Test
	void 키가_없으면_보내지_않는다() throws Exception {
		WebPushSender sender = new WebPushSender("", "", "mailto:x@invalid");
		assertFalse(sender.enabled());
		assertEquals(WebPushSender.Result.SKIPPED,
				sender.send(new WebPushSender.Subscription("https://example.invalid", "x", "y"), "{}").get());
	}
}
