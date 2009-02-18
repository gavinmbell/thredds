/*
 * Copyright 1998-2009 University Corporation for Atmospheric Research/Unidata
 *
 * Portions of this software were developed by the Unidata Program at the
 * University Corporation for Atmospheric Research.
 *
 * Access and use of this software shall impose the following obligations
 * and understandings on the user. The user is granted the right, without
 * any fee or cost, to use, copy, modify, alter, enhance and distribute
 * this software, and any derivative works thereof, and its supporting
 * documentation for any purpose whatsoever, provided that this entire
 * notice appears in all copies of the software, derivative works and
 * supporting documentation.  Further, UCAR requests that the user credit
 * UCAR/Unidata in any publications that result from the use of this
 * software or in any product that includes this software. The names UCAR
 * and/or Unidata, however, may not be used in any advertising or publicity
 * to endorse or promote any products or commercial entity unless specific
 * written permission is obtained from UCAR/Unidata. The user also
 * understands that UCAR/Unidata is not obligated to provide the user with
 * any support, consulting, training or assistance of any kind with regard
 * to the use, operation and performance of this software nor to provide
 * the user with any updates, revisions, new versions or "bug fixes."
 *
 * THIS SOFTWARE IS PROVIDED BY UCAR/UNIDATA "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL UCAR/UNIDATA BE LIABLE FOR ANY SPECIAL,
 * INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING
 * FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
 * NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION
 * WITH THE ACCESS, USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package ucar.nc2.stream.old;

import ucar.ma2.*;
import ucar.nc2.*;

import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.io.*;

/**
 * file = {segment}
 * segment = head_segment | data_segment
 * <p/>
 * head_segment = MAGIC_HEAD, {head_subsection}
 * head_subsection = MAGIC_DIM, dims | MAGIC_VAR, vars | MAGIC_ATT, atts
 * dims = ndim, {name, length, flags}
 * atts = natts, {att}
 * att = name, type, nvals, vals
 * vars = nvars, {var}
 * var = name, type, dims, atts
 * <p/>
 * data_segment = MAGIC_DATA, varname, elemSize, section, vals
 * section = nranges, {origin, size}
 * vals = {BYTE} | {SHORT} | {INT} | {LONG} | {FLOAT} | {DOUBLE} | {STRING}
 * <p/>
 * ndim, length, flags, natts, type, nvals, nvars, elemSize, nranges, origin, size = VLEN4
 * name, varname = STRING
 * type = TYPE_BYTE=1 | TYPE_SHORT=2 | TYPE_INT=3 | TYPE_FLOAT=4 | TYPE_DOUBLE=5 | TYPE_LONG=6 | TYPE_STRING=7
 *
 * @author caron
 * @since Jul 12, 2007
 */
public class StreamWriter {
  static final String MAGIC_HEADER = new String( new byte[]{0x43, 0x44, 0x46, 0x53, (byte) 0xec, (byte) 0xce, (byte) 0xad, (byte) 0xa0});
  static final String MAGIC = MAGIC_HEADER.substring(0,4);
  static final String HEADER = MAGIC_HEADER.substring(4);
  static final String MAGIC_DATA = new String( new byte[]{0x43, 0x44, 0x46, 0x53, (byte) 0xa1, (byte) 0x1a, (byte) 0xad, (byte) 0xa0});
  static final String DATA = MAGIC_DATA.substring(4);

  static final String MAGIC_ATTS = "Atts";
  static final String MAGIC_DIMS = "Dims";
  static final String MAGIC_VARS = "Vars";

  private NetcdfFile ncfile;
  private DataOutputStream out;
  private boolean debug = false;

  /**
   * Write the entire contents of a NetcdfFile out into a "stream format"
   *
   * @param ncfile write contents of this NetcdfFile
   * @param out    wrte to this stream
   * @throws IOException on i/o error
   */
  public StreamWriter(NetcdfFile ncfile, DataOutputStream out, boolean useRecord) throws IOException, InvalidRangeException {
    this.ncfile = ncfile;
    this.out = out;

    //writeMagic(MAGIC_FILE);

    if (useRecord) ncfile.sendIospMessage(NetcdfFile.IOSP_MESSAGE_ADD_RECORD_STRUCTURE);
    writeHeader(useRecord);

    List<Variable> vars = ncfile.getVariables();
    for (Variable v : vars) {
      if (!useRecord || !v.isUnlimited())
        writeData(v);
    }
    if (useRecord)
      writeRecordData();

    //writeMagic(MAGIC_EOF);
    out.flush();
  }

  int writeHeader(boolean useRecord) throws IOException {

    // magic number
    int count = writeMagic(MAGIC_HEADER);

    // dims
    List<Dimension> dims = ncfile.getDimensions();
    int ndims = dims.size();
    if (ndims > 0) {
      count += writeMagic(MAGIC_DIMS);
      writeDims(dims);
    }

    // global attributes
    List<Attribute> atts = ncfile.getGlobalAttributes();
    if (atts.size() > 0) {
      count += writeMagic(MAGIC_ATTS);
      count += writeAtts(atts);
    }

    // variables
    if (useRecord) {
      Structure record = (Structure) ncfile.findVariable("record");
      assert record != null;

      // non-record variables
      List<Variable> vars = new ArrayList(ncfile.getVariables());
      Iterator<Variable> iter = vars.iterator();
      while (iter.hasNext()) {
        Variable v = iter.next();
        if (v.isUnlimited() && v != record) iter.remove();
      }
      int nvars = vars.size();
      if (nvars > 0) {
        count += writeMagic(MAGIC_VARS);
        count += writeVInt(nvars);
        for (Variable v : vars) {
          count += writeVar(v);
        }
      }

      // record variables
      List<Variable> members = record.getVariables();
      nvars = members.size();
      if (nvars > 0) {
        count += writeMagic(MAGIC_VARS);
        count += writeVInt(nvars);
        for (Variable v : members) {
          count += writeVar(v);
        }
      }

    } else {

      List<Variable> vars = ncfile.getVariables();
      int nvars = vars.size();
      if (nvars > 0) {
        count += writeMagic(MAGIC_VARS);
        count += writeVInt(nvars);
        for (Variable v : vars) {
          count += writeVar(v);
        }
      }
    }

    return count;
  }

  int writeMagic(String magic) throws IOException {
    //assert magic.length() == 4;
    return writeBytes(magic.getBytes());
  }

  private int writeDims(List<Dimension> dims) throws IOException {
    int ndims = dims.size();
    int count = writeVInt(ndims);
    for (int i = 0; i < ndims; i++) {
      Dimension dim = dims.get(i);
      count += writeString(dim.getName());
      count += writeVInt(dim.getLength());
      int flags = dim.isShared() ? 1 : 0;
      if (dim.isUnlimited()) flags += 2;
      if (dim.isVariableLength()) flags += 4;
      count += writeByte((byte) flags);
    }
    return count;
  }

  private int writeVar(Variable var) throws IOException {
    int count = 0;
    count += writeString(var.getName());

    int type = getType(var.getDataType());
    count += writeVInt(type);

    // dimensions
    count += writeDims(var.getDimensions());

    // variable attributes
    count += writeAtts(var.getAttributes());

    return count;
  }


  private int writeAtts(List<Attribute> atts) throws IOException {
    int natts = atts.size();
    int count = writeVInt(natts);
    for (int i = 0; i < natts; i++) {
      Attribute att = atts.get(i);
      count += writeString(att.getName());

      int type = getType(att.getDataType());
      if (type == 2) type = 8;
      count += writeVInt(type);

      int nelems = att.getLength();
      count += writeVInt(nelems);

      if (type == 8) {
        for (int j = 0; j < nelems; j++)
          count += writeString(att.getStringValue(j));

      } else {
        for (int j = 0; j < nelems; j++)
          count += writeAttributeValue(att.getNumericValue(j));
      }
    }

    return count;
  }

  private int writeAttributeValue(Number numValue) throws IOException {
    if (numValue instanceof Byte) {
      out.write(numValue.byteValue());
      return 1;

    } else if (numValue instanceof Short) {
      out.writeShort(numValue.shortValue());
      return 2;

    } else if (numValue instanceof Integer) {
      out.writeInt(numValue.intValue());
      return 4;

    } else if (numValue instanceof Float) {
      out.writeFloat(numValue.floatValue());
      return 4;

    } else if (numValue instanceof Double) {
      out.writeDouble(numValue.doubleValue());
      return 8;
    }

    throw new IllegalStateException("unknown attribute type == " + numValue.getClass().getName());
  }

  static int getType(DataType dt) {
    if (dt == DataType.BYTE) return 1;
    else if (dt == DataType.CHAR) return 2;
    else if (dt == DataType.SHORT) return 3;
    else if (dt == DataType.INT) return 4;
    else if (dt == DataType.FLOAT) return 5;
    else if (dt == DataType.DOUBLE) return 6;
    else if (dt == DataType.LONG) return 7;
    else if (dt == DataType.STRING) return 8;
    else if (dt == DataType.STRUCTURE) return 9;

    throw new IllegalStateException("unknown DataType == " + dt);
  }

  static DataType getDataType(int code) {
    if (code == 1) return DataType.BYTE;
    else if (code == 2) return DataType.CHAR;
    else if (code == 3) return DataType.SHORT;
    else if (code == 4) return DataType.INT;
    else if (code == 5) return DataType.FLOAT;
    else if (code == 6) return DataType.DOUBLE;
    else if (code == 7) return DataType.LONG;
    else if (code == 8) return DataType.STRING;
    else if (code == 9) return DataType.STRUCTURE;

    throw new IllegalStateException("unknown DataType == " + code);
  }

  public int writeData(Variable v) throws IOException {
    int count = writeMagic(MAGIC_DATA);
    if (debug) System.out.println("  var= " + v.getNameAndDimensions() + " section = " + v.getShapeAsSection());

    count += writeString(v.getName());
    count += writeVInt(v.getElementSize());
    count += writeSection(v.getShapeAsSection());
    count += writeData(v.getDataType(), v.read());
    return count;
  }

  public int writeSection(Section s) throws IOException {
    int count = writeVInt(s.getRank());
    for (Range r : s.getRanges()) {
      count += writeVInt(r.first());
      count += writeVInt(r.length());
    }
    return count;
  }

  /////////////////////////////////////////////

  private int writeData(DataType dataType, Array values) throws java.io.IOException {

    if (dataType == DataType.BYTE) {
      byte[] pa = (byte[]) values.get1DJavaArray(byte.class);
      for (byte b : pa) out.write(b);
      return pa.length;

    } else if (dataType == DataType.CHAR) {
      char[] pa = (char[]) values.get1DJavaArray(char.class);
      for (char c : pa) out.write((byte) c);
      return pa.length;

    } else if (dataType == DataType.SHORT) {
      short[] pa = (short[]) values.get1DJavaArray(short.class);
      for (short s : pa) out.writeShort(s);
      return 2 * pa.length;

    } else if (dataType == DataType.INT) {
      int[] pa = (int[]) values.get1DJavaArray(int.class);
      for (int i : pa) out.writeInt(i);
      return 4 * pa.length;

    } else if (dataType == DataType.FLOAT) {
      float[] pa = (float[]) values.get1DJavaArray(float.class);
      for (float f : pa) out.writeFloat(f);
      return 4 * pa.length;

    } else if (dataType == DataType.DOUBLE) {
      double[] pa = (double[]) values.get1DJavaArray(double.class);
      for (double d : pa) out.writeDouble(d);
      return 8 * pa.length;

    } else if (dataType == DataType.STRING) {
      String[] pa = (String[]) values.get1DJavaArray(String.class);
      for (String s : pa) writeString(s);
      return 8 * pa.length;

    } else if (dataType == DataType.STRUCTURE) {
      int count = 0;
      ArrayStructure as = (ArrayStructure) values;
      IndexIterator ii = values.getIndexIterator();
      while (ii.hasNext()) {
        StructureData sdata = (StructureData) ii.getObjectNext();
        for (StructureMembers.Member m : sdata.getMembers()) {
          Array data = sdata.getArray(m);
          count += writeData(m.getDataType(), data); // recursive
        }
      }
      return count;
    }


    throw new IllegalStateException("dataType= " + dataType);
  }

  private int writeRecordData() throws java.io.IOException, InvalidRangeException {
    Structure record = (Structure) ncfile.findVariable("record");
    assert record != null;

    int recno = 0;
    int count = 0;
    StructureMembers sm = record.makeStructureMembers();
    int size = sm.getStructureSize();
    int nrecsPerSection = Math.max(1, (1000 * 1000)/size); // do about 1M at a time = nrecs
    int total_nrecs = (int) record.getSize();

    StructureDataIterator iter = record.getStructureIterator();
    while (iter.hasNext()) {
      if (recno % nrecsPerSection == 0) {
        int need = Math.min(nrecsPerSection, total_nrecs - recno);
        if (debug) System.out.println("  var= " + record.getNameAndDimensions() + " start = " + recno+" nrecs="+need);
        count += writeMagic(MAGIC_DATA); // each record is its own data section - could also do multiples
        count += writeString(record.getName());
        count += writeVInt(record.getElementSize());
        count += writeVInt(1);
        count += writeVInt(recno);
        count += writeVInt(need);
      }

      StructureData sdata = iter.next();
      for (StructureMembers.Member m : sdata.getMembers()) {
        Array data = sdata.getArray(m.getName());
        count += writeData(m.getDataType(), data);
      }
      recno++;
    }
    return count;
  }

  ////////////////////////////////////////
  // from org.apache.lucene.store.IndexOutput

  private int writeByte(byte b) throws IOException {
    out.write(b);
    return 1;
  }

  private int writeBytes(byte[] b, int offset, int length) throws IOException {
    out.write(b, offset, length);
    return length;
  }

  private int writeBytes(byte[] b) throws IOException {
    return writeBytes(b, 0, b.length);
  }

  private int writeVInt(int i) throws IOException {
    int count = 0;
    while ((i & ~0x7F) != 0) {
      writeByte((byte) ((i & 0x7f) | 0x80));
      i >>>= 7;
      count++;
    }
    writeByte((byte) i);
    return count + 1;
  }

  /**
   * Writes an long in a variable-length format.  Writes between one and five
   * bytes.  Smaller values take fewer bytes.  Negative numbers are not
   * supported.
   */
  private int writeVLong(long i) throws IOException {
    int count = 0;
    while ((i & ~0x7F) != 0) {
      writeByte((byte) ((i & 0x7f) | 0x80));
      i >>>= 7;
      count++;
    }
    writeByte((byte) i);
    return count + 1;
  }

  /**
   * Writes a string.
   */
  private int writeString(String s) throws IOException {
    int length = s.length();
    int count = writeVInt(length);
    count += writeChars(s, 0, length);
    return count;
  }

  /**
   * Writes a sequence of UTF-8 encoded characters from a string.
   *
   * @param s      the source of the characters
   * @param start  the first character in the sequence
   * @param length the number of characters in the sequence
   */
  private int writeChars(String s, int start, int length) throws IOException {
    final int end = start + length;
    int count = 0;
    for (int i = start; i < end; i++) {
      final int code = (int) s.charAt(i);
      if (code >= 0x01 && code <= 0x7F) {
        writeByte((byte) code);
        count++;
      } else if (((code >= 0x80) && (code <= 0x7FF)) || code == 0) {
        writeByte((byte) (0xC0 | (code >> 6)));
        writeByte((byte) (0x80 | (code & 0x3F)));
        count += 2;
      } else {
        writeByte((byte) (0xE0 | (code >>> 12)));
        writeByte((byte) (0x80 | ((code >> 6) & 0x3F)));
        writeByte((byte) (0x80 | (code & 0x3F)));
        count += 3;
      }
    }
    return count;
  }

}