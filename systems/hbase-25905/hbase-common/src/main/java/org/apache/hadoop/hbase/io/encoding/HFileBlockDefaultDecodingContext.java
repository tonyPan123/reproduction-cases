/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hadoop.hbase.io.encoding;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.io.ByteBuffInputStream;
import org.apache.hadoop.hbase.io.TagCompressionContext;
import org.apache.hadoop.hbase.io.compress.CanReinit;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hadoop.hbase.io.crypto.Cipher;
import org.apache.hadoop.hbase.io.crypto.Decryptor;
import org.apache.hadoop.hbase.io.crypto.Encryption;
import org.apache.hadoop.hbase.io.hfile.HFileContext;
import org.apache.hadoop.hbase.io.util.BlockIOUtils;
import org.apache.hadoop.hbase.nio.ByteBuff;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * A default implementation of {@link HFileBlockDecodingContext}. It assumes the
 * block data section is compressed as a whole.
 *
 * @see HFileBlockDefaultEncodingContext for the default compression context
 *
 */
@InterfaceAudience.Private
public class HFileBlockDefaultDecodingContext implements HFileBlockDecodingContext {
  private final Configuration conf;
  private final HFileContext fileContext;
  private TagCompressionContext tagCompressionContext;

  public HFileBlockDefaultDecodingContext(Configuration conf, HFileContext fileContext) {
    this.conf = conf;
    this.fileContext = fileContext;
  }

  @Override
  public void prepareDecoding(int onDiskSizeWithoutHeader, int uncompressedSizeWithoutHeader,
      ByteBuff blockBufferWithoutHeader, ByteBuff onDiskBlock) throws IOException {
    final ByteBuffInputStream byteBuffInputStream = new ByteBuffInputStream(onDiskBlock);
    InputStream dataInputStream = new DataInputStream(byteBuffInputStream);

    try {
      Encryption.Context cryptoContext = fileContext.getEncryptionContext();
      if (cryptoContext != Encryption.Context.NONE) {

        Cipher cipher = cryptoContext.getCipher();
        Decryptor decryptor = cipher.getDecryptor();
        decryptor.setKey(cryptoContext.getKey());

        // Encrypted block format:
        // +--------------------------+
        // | byte iv length           |
        // +--------------------------+
        // | iv data ...              |
        // +--------------------------+
        // | encrypted block data ... |
        // +--------------------------+

        int ivLength = dataInputStream.read();
        if (ivLength > 0) {
          byte[] iv = new byte[ivLength];
          IOUtils.readFully(dataInputStream, iv);
          decryptor.setIv(iv);
          // All encrypted blocks will have a nonzero IV length. If we see an IV
          // length of zero, this means the encoding context had 0 bytes of
          // plaintext to encode.
          decryptor.reset();
          dataInputStream = decryptor.createDecryptionStream(dataInputStream);
        }
        onDiskSizeWithoutHeader -= Bytes.SIZEOF_BYTE + ivLength;
      }

      Compression.Algorithm compression = fileContext.getCompression();
      if (compression != Compression.Algorithm.NONE) {
        Decompressor decompressor = null;
        try {
          decompressor = compression.getDecompressor();
          // Some algorithms don't return decompressors and accept null as a valid parameter for
          // same when creating decompression streams. We can ignore these cases wrt reinit.
          if (decompressor instanceof CanReinit) {
            ((CanReinit)decompressor).reinit(conf);
          }
          try (InputStream is =
              compression.createDecompressionStream(dataInputStream, decompressor, 0)) {
            BlockIOUtils.readFullyWithHeapBuffer(is, blockBufferWithoutHeader,
              uncompressedSizeWithoutHeader);
          }
        } finally {
          if (decompressor != null) {
            compression.returnDecompressor(decompressor);
          }
        }
      } else {
        BlockIOUtils.readFullyWithHeapBuffer(dataInputStream, blockBufferWithoutHeader,
          onDiskSizeWithoutHeader);
      }
    } finally {
      byteBuffInputStream.close();
      dataInputStream.close();
    }
  }

  @Override
  public HFileContext getHFileContext() {
    return this.fileContext;
  }

  public TagCompressionContext getTagCompressionContext() {
    return tagCompressionContext;
  }

  public void setTagCompressionContext(TagCompressionContext tagCompressionContext) {
    this.tagCompressionContext = tagCompressionContext;
  }
}
