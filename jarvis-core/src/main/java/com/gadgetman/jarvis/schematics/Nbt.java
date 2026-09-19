package com.gadgetman.jarvis.schematics;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal NBT reader and writer over plain Java maps and lists, enough for
 * schematic files. Compounds are {@code Map<String, Object>}, lists are
 * {@code List<Object>}, and the scalar and array tags map to their obvious
 * Java types.
 */
final class Nbt {

    private Nbt() { }

    // ==================== Reading ====================

    /** Read a root compound, discarding its name. */
    static Map<String, Object> read(DataInputStream dis) throws IOException {
        byte tagType = dis.readByte();
        if (tagType != 10) { // TAG_Compound
            throw new IOException("Expected TAG_Compound, got " + tagType);
        }
        dis.readUTF(); // Root name
        return readCompound(dis);
    }

    private static Map<String, Object> readCompound(DataInputStream dis) throws IOException {
        Map<String, Object> compound = new LinkedHashMap<>();
        byte tagType;
        while ((tagType = dis.readByte()) != 0) { // TAG_End
            String name = dis.readUTF();
            Object value = readTag(dis, tagType);
            compound.put(name, value);
        }
        return compound;
    }

    private static Object readTag(DataInputStream dis, byte tagType) throws IOException {
        switch (tagType) {
            case 1: return dis.readByte();
            case 2: return dis.readShort();
            case 3: return dis.readInt();
            case 4: return dis.readLong();
            case 5: return dis.readFloat();
            case 6: return dis.readDouble();
            case 7: {
                int length = dis.readInt();
                byte[] arr = new byte[length];
                dis.readFully(arr);
                return arr;
            }
            case 8: return dis.readUTF();
            case 9: {
                byte listType = dis.readByte();
                int length = dis.readInt();
                List<Object> list = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    list.add(readTag(dis, listType));
                }
                return list;
            }
            case 10: return readCompound(dis);
            case 11: {
                int length = dis.readInt();
                int[] arr = new int[length];
                for (int i = 0; i < length; i++) {
                    arr[i] = dis.readInt();
                }
                return arr;
            }
            case 12: {
                int length = dis.readInt();
                long[] arr = new long[length];
                for (int i = 0; i < length; i++) {
                    arr[i] = dis.readLong();
                }
                return arr;
            }
            default:
                throw new IOException("Unknown tag type: " + tagType);
        }
    }

    // ==================== Writing ====================

    /** Write a named root compound. */
    static void write(DataOutputStream dos, String name, Map<String, Object> compound) throws IOException {
        dos.writeByte(10); // TAG_Compound
        dos.writeUTF(name);
        writeCompound(dos, compound);
    }

    private static void writeCompound(DataOutputStream dos, Map<String, Object> compound) throws IOException {
        for (Map.Entry<String, Object> entry : compound.entrySet()) {
            byte tagType = tagType(entry.getValue());
            dos.writeByte(tagType);
            dos.writeUTF(entry.getKey());
            writeTagValue(dos, entry.getValue(), tagType);
        }
        dos.writeByte(0); // TAG_End
    }

    @SuppressWarnings("unchecked")
    private static void writeTagValue(DataOutputStream dos, Object value, byte tagType) throws IOException {
        switch (tagType) {
            case 1: dos.writeByte((Byte) value); break;
            case 2: dos.writeShort((Short) value); break;
            case 3: dos.writeInt((Integer) value); break;
            case 4: dos.writeLong((Long) value); break;
            case 5: dos.writeFloat((Float) value); break;
            case 6: dos.writeDouble((Double) value); break;
            case 7: {
                byte[] arr = (byte[]) value;
                dos.writeInt(arr.length);
                dos.write(arr);
                break;
            }
            case 8: dos.writeUTF((String) value); break;
            case 9: {
                List<Object> list = (List<Object>) value;
                byte listType = list.isEmpty() ? 0 : tagType(list.get(0));
                dos.writeByte(listType);
                dos.writeInt(list.size());
                for (Object item : list) {
                    writeTagValue(dos, item, listType);
                }
                break;
            }
            case 10: writeCompound(dos, (Map<String, Object>) value); break;
            case 11: {
                int[] arr = (int[]) value;
                dos.writeInt(arr.length);
                for (int i : arr) dos.writeInt(i);
                break;
            }
            case 12: {
                long[] arr = (long[]) value;
                dos.writeInt(arr.length);
                for (long l : arr) dos.writeLong(l);
                break;
            }
            default: break;
        }
    }

    private static byte tagType(Object value) {
        if (value instanceof Byte) return 1;
        if (value instanceof Short) return 2;
        if (value instanceof Integer) return 3;
        if (value instanceof Long) return 4;
        if (value instanceof Float) return 5;
        if (value instanceof Double) return 6;
        if (value instanceof byte[]) return 7;
        if (value instanceof String) return 8;
        if (value instanceof List) return 9;
        if (value instanceof Map) return 10;
        if (value instanceof int[]) return 11;
        if (value instanceof long[]) return 12;
        return 0;
    }

    // ==================== VarInt ====================

    static void writeVarInt(OutputStream os, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            os.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        os.write(value);
    }

    /** Decode a varint-packed array of {@code expectedSize} values. */
    static int[] readVarIntArray(byte[] data, int expectedSize) {
        int[] result = new int[expectedSize];
        int dataIndex = 0;
        int resultIndex = 0;

        while (dataIndex < data.length && resultIndex < expectedSize) {
            int value = 0;
            int shift = 0;

            while (true) {
                if (dataIndex >= data.length) break;
                byte b = data[dataIndex++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }

            result[resultIndex++] = value;
        }

        return result;
    }
}
