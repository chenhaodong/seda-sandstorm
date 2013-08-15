/*
 * Copyright (c) 1999, 2000
 * Lehrstuhl fuer Prozessleittechnik (PLT), RWTH Aachen
 * D-52064 Aachen, Germany.
 * All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Library General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this program (see the file COPYING.LIB for more
 * details); if not, write to the Free Software Foundation, Inc.,
 * 675 Mass Ave, Cambridge, MA 02139, USA.
 */

package seda.sandStorm.lib.util;

import seda.sandStorm.api.*;
import seda.sandStorm.core.*;
import java.util.*;

/**
 * Encode and decode Base64 data. 
 */
public abstract class Base64 {

  private static final boolean DEBUG = false;

  /**
   * Converts data into base64 encoded data.
   *
   * @param data Data to be encoded.
   * @return A BufferElement with the data encoded in Base64.
   */
  public static BufferElement encode(BufferElement data) {

    byte input[] = data.getBytes();
    int offset = data.getOffset();
    int length = data.getSize();

    // Calculate length of encoded data including optional padding.
    int encodedLength = ((length + 2) / 3) * 4;
    byte output[] = new byte[encodedLength];
    int outoffset = 0;

    // Now do the encoding, thus inflating every three bytes of binary
    // data to four ASCII characters.
    int b1, b2, b3;
    int endPos = offset + length - 1 - 2;
    while (offset <= endPos) {
      b1 = input[offset++];
      b2 = input[offset++];
      b3 = input[offset++];

      output[outoffset++] = encodingBase64Alphabet[(b1 >>> 2) & 0x3F];
      output[outoffset++] = encodingBase64Alphabet[((b1 << 4) & 0x30) | ((b2 >>> 4) & 0xF)];
      output[outoffset++] = encodingBase64Alphabet[((b2 << 2) & 0x3C) | ((b3 >>> 6) & 0x03)];
      output[outoffset++] = encodingBase64Alphabet[b3 & 0x3F];
    }

    // If one or two bytes are left (because we work on blocks of three
    // bytes), convert them too and apply padding.

    endPos += 2; // now points to the last encodable byte
    if (offset <= endPos) {
      b1 = input[offset++];
      output[outoffset++] = encodingBase64Alphabet[(b1 >>> 2) & 0x3F];
      if (offset <= endPos) {
	b2 = input[offset++]; 
	output[outoffset++] = encodingBase64Alphabet[((b1 << 4) & 0x30) | ((b2 >>> 4) & 0xF)];
	output[outoffset++] = encodingBase64Alphabet[(b2 << 2) & 0x3C];
	output[outoffset] = '=';
      } else {
	output[outoffset++] = encodingBase64Alphabet[(b1 << 4) & 0x30];
	output[outoffset++] = '=';
	output[outoffset] = '=';
      }
    }
    return new BufferElement(output);
  }

  /**
   * Converts Base64 encoded data into binary data.
   *
   * @param data Base64 encoded data.
   * @return A BufferElement containing the decoded data.
   */
  public static BufferElement decode(BufferElement data) {

    byte input[] = data.getBytes();
    int offset = data.getOffset();
    int length = data.getSize();

    // Check if we need to squash the data
    if ((offset != 0) || (length != input.length)) {
      byte input2[] = new byte[length];
      System.arraycopy(input, offset, input2, 0, length);
      input = input2;
    }

    // Strip out whitespace 
    StringTokenizer st = new StringTokenizer(new String(input), " \t\r\n", false);
    String newinput = new String();
    while (st.hasMoreTokens()) {
      newinput += st.nextToken();
    }
    input = newinput.getBytes();
    offset = 0;
    length = input.length;

    // Calculate length of decoded data including optional padding.
    int endPos = offset + length - 1;
    // Determine the length of data to be decoded. Optional padding has
    // to be removed first.
    if (DEBUG) System.err.println("endPos: "+endPos);
    while ((endPos >= 0) && 
	((input[endPos] == '=') ||
 	 (input[endPos] == '\r') ||
	 (input[endPos] == '\n'))) {
      endPos--;
    }
    if (DEBUG) System.err.println("endPos fixed to: "+endPos);

    int decodedLength = endPos - offset - length / 4 + 1;
    byte output[] = new byte[decodedLength];
    int outoffset = 0;

    // Now do the four-to-three entities/letters/bytes/whatever
    // conversion. We chew on as many four-letter groups as we can,
    // converting them into three byte groups.

    byte b1, b2, b3, b4;
    // This points to the last letter in the last four-letter group
    int stopPos = endPos - 3; 
    while (offset <= stopPos) {
      if (DEBUG) System.err.print("b1: "+input[offset]+" -> ");
      b1 = decodingBase64Alphabet[input[offset++]];
      if (DEBUG) System.err.print(b1+"\nb2: "+input[offset]+" -> ");
      b2 = decodingBase64Alphabet[input[offset++]];
      if (DEBUG) System.err.print(b2+"\nb3: "+input[offset]+" -> ");
      b3 = decodingBase64Alphabet[input[offset++]];
      if (DEBUG) System.err.print(b3+"\nb4: "+input[offset]+" -> ");
      b4 = decodingBase64Alphabet[input[offset++]];
      if (DEBUG) System.err.println(b4);
      output[outoffset++] = (byte)(((b1 << 2) & 0xFF) | ((b2 >>> 4) & 0x03));
      output[outoffset++] = (byte)(((b2 << 4) & 0xFF) | ((b3 >>> 2) & 0x0F));
      output[outoffset++] = (byte)(((b3 << 6) & 0xFF) | (b4 & 0x3F));
    }

    // If one, two or three letters from the base64 encoded data are
    // left, convert them too.
    // Hack Note(tm): if the length of encoded data is not a multiple
    // of four, then padding must occur ('='). As the decoding alphabet
    // contains zeros everywhere with the exception of valid letters,
    // indexing into the mapping is just fine and reliefs us of the
    // pain to check everything and make thus makes the code better.

    if (DEBUG) System.err.println("offset "+offset+", endPos "+endPos+", length "+length);

    if (offset <= endPos) {
      if (DEBUG) System.err.print("END b1: "+input[offset]+" -> ");
      b1 = decodingBase64Alphabet[input[offset++]];
      if (DEBUG) System.err.print(b1+"\nEND b2: "+input[offset]+" -> ");
      b2 = decodingBase64Alphabet[input[offset++]];
      if (DEBUG) System.err.println(b2);
      output[outoffset++] = (byte)(((b1 << 2) & 0xFF) | ((b2 >>> 4) & 0x03));
      if (offset <= endPos) { 
	b3 = decodingBase64Alphabet[input[offset]];
	output[outoffset++] = (byte)(((b2 << 4) & 0xFF) | ((b3 >>> 2) & 0x0F));
      }
    }
    return new BufferElement(output);
  }

  /**
   * Mapping from binary 0-63 to base64 alphabet according to RFC 2045.
   */
  private static final byte [] encodingBase64Alphabet = {
    'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
    'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
    'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
    'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '+', '/'
  };

  /**
   * Mapping from base64 alphabet to binary 0-63.
   */
  private static final byte [] decodingBase64Alphabet;

  /**
   * The class initializer is responsible to set up the mapping from the
   * base64 alphabet (ASCII-based) to binary 0-63.
   */
  static {
    decodingBase64Alphabet = new byte[256];
    for ( int i = 0; i < 64; ++i ) {
      decodingBase64Alphabet[encodingBase64Alphabet[i]] = (byte)(i & 0xff);
    }
  }

}

