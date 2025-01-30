/*
 * Copyright 2017-2021 The Technical University of Denmark
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.dtu.compute.mavis.utils;

import dk.dtu.compute.mavis.server.Server;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

public class Crypto
{
    public static void generateNewRsaKeyPair(Path publicKeyPath, Path privateKeyPath)
    throws NoSuchAlgorithmException, IOException
    {
        if (Files.exists(publicKeyPath)) {
            throw new RuntimeException("Public key file already exists: " + publicKeyPath);
        }
        if (Files.exists(privateKeyPath)) {
            throw new RuntimeException("Private key file already exists: " + privateKeyPath);
        }

        // We just intend to deter from tampering with the output file, not to be cryptographically secure.
        // A small key should be alright for this.
        var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(1024);

        var keyPair = keyPairGenerator.generateKeyPair();
        Files.write(publicKeyPath, keyPair.getPublic().getEncoded(), StandardOpenOption.CREATE_NEW);
        Server.printInfo("Wrote public key to: " + publicKeyPath);
        Files.write(privateKeyPath, keyPair.getPrivate().getEncoded(), StandardOpenOption.CREATE_NEW);
        Server.printInfo("Wrote private key to: " + privateKeyPath);
    }

    public static CipherOutputStream newCipherOutputStream(Path publicKeyPath, OutputStream out)
    throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeySpecException, IOException, InvalidKeyException,
           InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException
    {
        // Generate AES key.
        var aesKeyGenerator = KeyGenerator.getInstance("AES");
        aesKeyGenerator.init(128);
        var aesKey = aesKeyGenerator.generateKey();

        // Load RSA public key.
        var rsaKeyFactory = KeyFactory.getInstance("RSA");
        var publicKeyBytes = Files.readAllBytes(publicKeyPath);
        var publicKeyEncoded = new X509EncodedKeySpec(publicKeyBytes);
        var publicKey = rsaKeyFactory.generatePublic(publicKeyEncoded);

        // Encrypt AES key with RSA.
        var rsaCipher = Cipher.getInstance("RSA");
        rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey);
        var encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

        // Write encrypted AES key (prefixed by its size in bytes) to stream.
        var dataOutputStream = new DataOutputStream(out);
        dataOutputStream.writeInt(encryptedAesKey.length);
        dataOutputStream.write(encryptedAesKey);
        dataOutputStream.flush();

        // Generate IV for AES and write it to stream.
        var ivBytes = new byte[16];
        new SecureRandom().nextBytes(ivBytes);
        var iv = new IvParameterSpec(ivBytes);
        out.write(ivBytes);

        // Create AES cipher wrapping stream.
        var aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, iv);
        return new CipherOutputStream(out, aesCipher);
    }

    public static CipherInputStream newCipherInputStream(Path privateKeyPath, InputStream in)
    throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeySpecException, IOException, InvalidKeyException,
           IllegalBlockSizeException, BadPaddingException, InvalidAlgorithmParameterException
    {
        // Read AES key.
        var dataInputStream = new DataInputStream(in);
        var aesKeyEncryptedSize = dataInputStream.readInt();
        var aesKeyEncrypted = dataInputStream.readNBytes(aesKeyEncryptedSize);

        // Read IV.
        var iv = new IvParameterSpec(in.readNBytes(16));

        // Load RSA private key.
        var rsaKeyFactory = KeyFactory.getInstance("RSA");
        var privateKeyBytes = Files.readAllBytes(privateKeyPath);
        var privateKeyEncoded = new PKCS8EncodedKeySpec(privateKeyBytes);
        var privateKey = rsaKeyFactory.generatePrivate(privateKeyEncoded);

        // Decrypt AES key.
        var rsaCipher = Cipher.getInstance("RSA");
        rsaCipher.init(Cipher.DECRYPT_MODE, privateKey);
        var aesKeyEncoded = rsaCipher.doFinal(aesKeyEncrypted);
        var aesKey = new SecretKeySpec(aesKeyEncoded, "AES");

        // Wrap remaining input stream for AES decryption.
        var aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aesCipher.init(Cipher.DECRYPT_MODE, aesKey, iv);
        return new CipherInputStream(in, aesCipher);
    }
}
