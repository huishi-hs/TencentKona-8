/*
 *
 * Copyright (C) 2020 THL A29 Limited, a Tencent company. All rights reserved.
 * DO NOT ALTER OR REMOVE NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as
 * published by the Free Software Foundation. THL A29 Limited designates
 * this particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License version 2 for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */
package com.tencent.crypto.provider.Cipher.SM4;

import com.tencent.crypto.provider.SM4GCMParameterSpec;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidParameterSpecException;

import static java.lang.System.out;

/**
 * This is a abstract class used to test various ciphers
 */
public abstract class TestCipher {

    private final String SMCSProvider = "SMCSProvider";
    private final String ALGORITHM;
    private final String[] MODES;
    private final String[] PADDINGS;

    /* Used to test variable-key-length ciphers:
       Key size tested is increment of KEYCUTTER from minKeySize
       to min(maxKeySize, Cipher.getMaxAllowedKeyLength(algo)).
    */
    private final int KEYCUTTER = 8;
    private final int minKeySize;
    private final int maxKeySize;

    // Used to assert that Encryption/Decryption works with same buffer
    // TEXT_LEN is multiple of blocks in order to work against ciphers w/ NoPadding
    private final int TEXT_LEN = 800;
    private final int ENC_OFFSET = 6;
    private final int STORAGE_OFFSET = 3;
    private final int PAD_BYTES = 16;

    private final byte[] IV;
    private final byte[] INPUT_TEXT;

    // for variable-key-length ciphers
    TestCipher(String algo, String[] modes, String[] paddings,
               int minKeySize, int maxKeySize) throws NoSuchAlgorithmException {
        ALGORITHM = algo;
        MODES = modes;
        PADDINGS = paddings;
        this.minKeySize = minKeySize;
        int maxAllowedKeySize = Cipher.getMaxAllowedKeyLength(ALGORITHM);
        if (maxKeySize > maxAllowedKeySize) {
            maxKeySize = maxAllowedKeySize;
        }
        this.maxKeySize = maxKeySize;
        IV = generateBytes(16);
        INPUT_TEXT = generateBytes(TEXT_LEN + PAD_BYTES + ENC_OFFSET);
    }

    // for fixed-key-length ciphers
    TestCipher(String algo, String[] modes, String[] paddings) {
        ALGORITHM = algo;
        MODES = modes;
        PADDINGS = paddings;
        this.minKeySize = this.maxKeySize = 0;

        IV = generateBytes(16);
        INPUT_TEXT = generateBytes(TEXT_LEN + PAD_BYTES + ENC_OFFSET);
    }

    private static byte[] generateBytes(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i & 0xff);
        }
        return bytes;
    }

    private boolean isMultipleKeyLengthSupported() {
        return (maxKeySize != minKeySize);
    }

    public void runAll() throws InvalidKeyException,
            NoSuchPaddingException, InvalidAlgorithmParameterException,
            ShortBufferException, IllegalBlockSizeException,
            BadPaddingException, NoSuchAlgorithmException,
            NoSuchProviderException {

        for (String mode : MODES) {
            for (String padding : PADDINGS) {
                if (mode.equalsIgnoreCase("CTR") && padding.equalsIgnoreCase("PKCS7Padding")) {
                    // skip unsupported mode.
                    continue;
                }
                if (!isMultipleKeyLengthSupported()) {
                    runTest(mode, padding, minKeySize);
                } else {
                    int keySize = maxKeySize;
                    while (keySize >= minKeySize) {
                        out.println("With Key Strength: " + keySize);
                        runTest(mode, padding, keySize);
                        keySize -= KEYCUTTER;
                    }
                }
            }
        }
    }

    private void runTest(String mo, String pad, int keySize)
            throws NoSuchPaddingException, BadPaddingException,
            ShortBufferException, IllegalBlockSizeException,
            InvalidAlgorithmParameterException, InvalidKeyException,
            NoSuchAlgorithmException, NoSuchProviderException {

        String TRANSFORMATION = ALGORITHM + "/" + mo + "/" + pad;
        out.println("Testing: " + TRANSFORMATION);

        // Initialization
        Cipher ci = Cipher.getInstance(TRANSFORMATION, SMCSProvider);
        KeyGenerator kg = KeyGenerator.getInstance(ALGORITHM, SMCSProvider);
        if (keySize != 0) {
            kg.init(keySize);
        }

        SecretKey key = kg.generateKey();
        SecretKeySpec skeySpec = new SecretKeySpec(key.getEncoded(), ALGORITHM);

        AlgorithmParameterSpec aps = null;
        if (!mo.equalsIgnoreCase("GCM")) {
            aps = new IvParameterSpec(IV);
        }

        if (mo.equalsIgnoreCase("ECB")) {
            ci.init(Cipher.ENCRYPT_MODE, key);
        } else {
            ci.init(Cipher.ENCRYPT_MODE, key, aps);
        }

        // Encryption
        byte[] plainText = INPUT_TEXT.clone();

        // Generate cipher and save to separate buffer
        byte[] cipherText = ci.doFinal(INPUT_TEXT, ENC_OFFSET, TEXT_LEN);

        // Generate cipher and save to same buffer
        int enc_bytes = ci.update(
                INPUT_TEXT, ENC_OFFSET, TEXT_LEN, INPUT_TEXT, STORAGE_OFFSET);
        enc_bytes += ci.doFinal(INPUT_TEXT, enc_bytes + STORAGE_OFFSET);

        if (!equalsBlock(
                INPUT_TEXT, STORAGE_OFFSET, enc_bytes,
                cipherText, 0, cipherText.length)) {
            throw new RuntimeException(
                    "Different ciphers generated with same buffer");
        }

        // Decryption
        if (mo.equalsIgnoreCase("ECB")) {
            ci.init(Cipher.DECRYPT_MODE, skeySpec);
        } else if (mo.equalsIgnoreCase("GCM")) {
            SM4GCMParameterSpec gmcParams = null;
            try {
                gmcParams = ci.getParameters().getParameterSpec(SM4GCMParameterSpec.class);
            } catch (InvalidParameterSpecException e) {
                e.printStackTrace();
            }
            ci.init(Cipher.DECRYPT_MODE, skeySpec, gmcParams);
        } else {
            ci.init(Cipher.DECRYPT_MODE, skeySpec, aps);
        }

        // Recover text from cipher and save to separate buffer
        byte[] recoveredText = ci.doFinal(cipherText, 0, cipherText.length);

        if (!equalsBlock(
                plainText, ENC_OFFSET, TEXT_LEN,
                recoveredText, 0, recoveredText.length)) {
            throw new RuntimeException(
                    "Recovered text not same as plain text");
        } else {
            out.println("Recovered and plain text are same");
        }

        // Recover text from cipher and save to same buffer
        int dec_bytes = ci.update(
                INPUT_TEXT, STORAGE_OFFSET, enc_bytes, INPUT_TEXT, ENC_OFFSET);
        dec_bytes += ci.doFinal(INPUT_TEXT, dec_bytes + ENC_OFFSET);

        if (!equalsBlock(
                plainText, ENC_OFFSET, TEXT_LEN,
                INPUT_TEXT, ENC_OFFSET, dec_bytes)) {
            throw new RuntimeException(
                    "Recovered text not same as plain text with same buffer");
        } else {
            out.println("Recovered and plain text are same with same buffer");
        }

        out.println("Test Passed.");
    }

    private static boolean equalsBlock(byte[] b1, int off1, int len1,
                                       byte[] b2, int off2, int len2) {
        if (len1 != len2) {
            return false;
        }
        for (int i = off1, j = off2, k = 0; k < len1; i++, j++, k++) {
            if (b1[i] != b2[j]) {
                return false;
            }
        }
        return true;
    }
}
