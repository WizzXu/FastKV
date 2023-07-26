package io.fastkv;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.fastkv.Container.*;
import io.fastkv.interfaces.FastEncoder;
import io.fastkv.interfaces.FastCipher;
import io.fastkv.interfaces.FastLogger;

/**
 * Abstract class of FastKV and MPFastKV.
 */
@SuppressWarnings("rawtypes")
abstract class AbsFastKV {
    protected static final String BOTH_FILES_ERROR = "both files error";
    protected static final String PARSE_DATA_FAILED = "parse dara failed";
    protected static final String OPEN_FILE_FAILED = "open file failed";
    protected static final String MAP_FAILED = "map failed";
    protected static final String MISS_CIPHER = "miss cipher";

    static final String TRUNCATE_FINISH = "truncate finish";
    static final String GC_FINISH = "gc finish";

    protected static final String A_SUFFIX = ".kva";
    protected static final String B_SUFFIX = ".kvb";
    protected static final String C_SUFFIX = ".kvc";
    protected static final String TEMP_SUFFIX = ".tmp";

    // Use for transfer data from not encrypted to encrypted.
    protected static final String TEMP_PREFIX = "temp_";

    protected static final int DATA_SIZE_LIMIT = 1 << 28; // 256M
    protected static final int CIPHER_MASK = 1 << 30;

    protected static final int[] TYPE_SIZE = {0, 1, 4, 4, 8, 8};
    protected static final byte[] EMPTY_ARRAY = new byte[0];
    protected static final int DATA_START = 12;
    protected static final int BASE_GC_KEYS_THRESHOLD = 80;
    protected static final int BASE_GC_BYTES_THRESHOLD = 4096;
    protected final int INTERNAL_LIMIT = FastKVConfig.internalLimit;

    protected static final int PAGE_SIZE = Util.getPageSize();
    protected static final int DOUBLE_LIMIT = Math.max(PAGE_SIZE << 1, 1 << 14);
    protected static final int TRUNCATE_THRESHOLD = DOUBLE_LIMIT << 1;

    protected final String path;
    protected final String name;
    protected final Map<String, FastEncoder> encoderMap;
    protected final FastLogger logger = FastKVConfig.sLogger;
    protected final FastCipher cipher;

    protected int dataEnd;
    protected long checksum;
    protected final HashMap<String, BaseContainer> data = new HashMap<>();
    protected volatile boolean startLoading = false;

    protected FastBuffer fastBuffer;
    protected int updateStart;
    protected int updateSize;

    protected final List<String> deletedFiles = new ArrayList<>();

    // If the kv had not encrypted before, and need to encrypt this time,
    // It has to rewrite the data.
    protected boolean needRewrite = false;

    protected String tempExternalName;
    protected final WeakCache externalCache = new WeakCache();
    protected final WeakCache bigValueCache = new WeakCache();

    private final TagExecutor externalExecutor = new TagExecutor();

    protected int invalidBytes;
    protected final ArrayList<Segment> invalids = new ArrayList<>();

    protected AbsFastKV(final String path, final String name, FastEncoder[] encoders, FastCipher cipher) {
        this.path = path;
        this.name = name;
        this.cipher = cipher;
        Map<String, FastEncoder> map = new HashMap<>();
        if (encoders != null) {
            for (FastEncoder e : encoders) {
                String tag = e.tag();
                if (map.containsKey(tag)) {
                    error("duplicate encoder tag:" + tag);
                } else {
                    map.put(tag, e);
                }
            }
        }
        StringSetEncoder encoder = StringSetEncoder.INSTANCE;
        map.put(encoder.tag(), encoder);
        this.encoderMap = map;
    }

    protected final int packSize(int size) {
        return cipher == null ? size : size | CIPHER_MASK;
    }

    protected static int unpackSize(int size) {
        return size & (~CIPHER_MASK);
    }

    protected static boolean isCipher(int size) {
        return (size & CIPHER_MASK) != 0;
    }

    protected final int getNewCapacity(int capacity, int expected) {
        if (expected >= DATA_SIZE_LIMIT) {
            throw new IllegalStateException("data size out of limit");
        }
        if (expected <= PAGE_SIZE) {
            return PAGE_SIZE;
        } else {
            while (capacity < expected) {
                if (capacity <= DOUBLE_LIMIT) {
                    capacity <<= 1;
                } else {
                    capacity += DOUBLE_LIMIT;
                }
            }
            return capacity;
        }
    }

    protected void rewrite() {
        FastEncoder[] encoders = new FastEncoder[encoderMap.size()];
        encoders = encoderMap.values().toArray(encoders);
        String tempName = TEMP_PREFIX + name;

        // Here we use FastKV with blocking mode and close 'autoCommit',
        // to make data only keep on memory.
        FastKV tempKV = new FastKV(path, tempName, encoders, cipher, 2/*SYNC_BLOCKING*/);
        tempKV.autoCommit = false;

        List<String> oldExternalFiles = new ArrayList<>();
        for (Map.Entry<String, BaseContainer> entry : data.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof StringContainer) {
                StringContainer c = (StringContainer) value;
                if (c.external) {
                    oldExternalFiles.add((String) c.value);
                    String bigStr = getStringFromFile(c, null);
                    if(bigStr != null){
                        tempKV.putString(key, bigStr);
                    }
                } else {
                    tempKV.putString(key, (String) c.value);
                }
            } else if (value instanceof BooleanContainer) {
                tempKV.putBoolean(key, ((BooleanContainer) value).value);
            } else if (value instanceof IntContainer) {
                tempKV.putInt(key, ((IntContainer) value).value);
            } else if (value instanceof LongContainer) {
                tempKV.putLong(key, ((LongContainer) value).value);
            } else if (value instanceof FloatContainer) {
                tempKV.putFloat(key, ((FloatContainer) value).value);
            } else if (value instanceof DoubleContainer) {
                tempKV.putDouble(key, ((DoubleContainer) value).value);
            } else if (value instanceof ArrayContainer) {
                ArrayContainer c = (ArrayContainer) value;
                if (c.external) {
                    oldExternalFiles.add((String) c.value);
                    byte[] bigArray = getArrayFromFile(c, null);
                    if (bigArray != null) {
                        tempKV.putArray(key, bigArray);
                    }
                } else {
                    tempKV.putArray(key, (byte[]) c.value);
                }
            } else if (value instanceof ObjectContainer) {
                ObjectContainer c = (ObjectContainer) value;
                if (c.external) {
                    oldExternalFiles.add((String) c.value);
                    Object obj = getObjectFromFile(c, null);
                    if (obj != null && c.encoder != null) {
                        //noinspection unchecked
                        tempKV.putObject(key, obj, c.encoder);
                    }
                } else if (c.encoder != null) {
                    //noinspection unchecked
                    tempKV.putObject(key, c.value, c.encoder);
                }
            }
        }

        // 'loadData()' of FastKV is a method execute in async thread, and it's a synchronized method.
        //  To ensure tempKV loading finish,
        //  calling another synchronized method to block current process (if loading not finish).
        tempKV.contains("");

        // Copy memory data
        fastBuffer = tempKV.fastBuffer;
        checksum = tempKV.checksum;
        dataEnd = tempKV.dataEnd;
        clearInvalid();
        data.clear();
        data.putAll(tempKV.data);

        copyToMainFile(tempKV);

        // Move external files
        File tempDir = new File(path, tempName);
        String currentDir = path + name;
        Util.moveDirFiles(tempDir, currentDir);
        Util.deleteFile(tempDir);
        for (String name : oldExternalFiles) {
            Util.deleteFile(new File(currentDir, name));
        }

        needRewrite = false;
    }

    protected abstract void copyToMainFile(FastKV tempKV);

    protected final boolean loadWithBlockingIO(File srcFile) throws IOException {
        long fileLen = srcFile.length();
        if (fileLen == 0 || fileLen >= DATA_SIZE_LIMIT) {
            return false;
        }
        int fileSize = (int) fileLen;
        int capacity = getNewCapacity(PAGE_SIZE, fileSize);
        FastBuffer buffer;
        if (fastBuffer != null && fastBuffer.hb.length == capacity) {
            buffer = fastBuffer;
            buffer.position = 0;
        } else {
            buffer = new FastBuffer(new byte[capacity]);
            fastBuffer = buffer;
        }
        Util.readBytes(srcFile, buffer.hb, fileSize);
        int size = buffer.getInt();
        if (size < 0) {
            return false;
        }
        int dataSize = unpackSize(size);
        boolean hadEncrypted = isCipher(size);
        long sum = buffer.getLong();
        dataEnd = DATA_START + dataSize;
        if (dataSize >= 0 && (dataSize <= fileSize - DATA_START)
                && sum == buffer.getChecksum(DATA_START, dataSize)
                && parseData(hadEncrypted)) {
            checksum = sum;
            return true;
        }
        return false;
    }

    protected final void deleteCFiles() {
        try {
            Util.deleteFile(new File(path, name + C_SUFFIX));
            Util.deleteFile(new File(path, name + TEMP_SUFFIX));
        } catch (Exception e) {
            error(e);
        }
    }

    protected final boolean parseData(boolean hadEncrypted) {
        if (hadEncrypted && cipher == null) {
            error(MISS_CIPHER);
            return false;
        }
        FastCipher dataCipher = hadEncrypted ? cipher : null;
        FastBuffer buffer = fastBuffer;
        buffer.position = DATA_START;
        try {
            while (buffer.position < dataEnd) {
                int start = buffer.position;
                byte info = buffer.get();
                byte type = (byte) (info & DataType.TYPE_MASK);
                if (type < DataType.BOOLEAN || type > DataType.OBJECT) {
                    throw new Exception(PARSE_DATA_FAILED);
                }
                int keySize = buffer.get() & 0xFF;
                if (keySize == 0) {
                    throw new IllegalStateException("invalid key size");
                }
                if (info < 0) {
                    buffer.position += keySize;
                    int valueSize = (type <= DataType.DOUBLE) ? TYPE_SIZE[type] : buffer.getShort() & 0xFFFF;
                    buffer.position += valueSize;
                    countInvalid(start, buffer.position);
                    continue;
                }
                String key = buffer.getString(dataCipher, keySize);
                int pos = buffer.position;
                if (type <= DataType.DOUBLE) {
                    switch (type) {
                        case DataType.BOOLEAN:
                            data.put(key, new BooleanContainer(pos, buffer.get() == 1));
                            break;
                        case DataType.INT:
                            data.put(key, new IntContainer(pos, buffer.getInt(dataCipher)));
                            break;
                        case DataType.LONG:
                            data.put(key, new LongContainer(pos, buffer.getLong(dataCipher)));
                            break;
                        case DataType.FLOAT:
                            data.put(key, new FloatContainer(pos, buffer.getFloat(dataCipher)));
                            break;
                        default:
                            data.put(key, new DoubleContainer(pos, buffer.getDouble(dataCipher)));
                            break;
                    }
                } else {
                    int size = buffer.getShort() & 0xFFFF;
                    boolean external = (info & DataType.EXTERNAL_MASK) != 0;
                    if (external && size != Util.NAME_SIZE) {
                        throw new IllegalStateException("name size not match");
                    }
                    switch (type) {
                        case DataType.STRING:
                            String str = external ? buffer.getString(size) : buffer.getString(dataCipher, size);
                            data.put(key, new StringContainer(start, pos + 2, str, size, external));
                            break;
                        case DataType.ARRAY:
                            Object value = external ? buffer.getString(size) : buffer.getBytes(dataCipher, size);
                            data.put(key, new ArrayContainer(start, pos + 2, value, size, external));
                            break;
                        default:
                            if (external) {
                                String fileName = buffer.getString(size);
                                data.put(key, new ObjectContainer(start, pos + 2, fileName, size, true));
                            } else {
                                parseObject(size, key, start, pos, dataCipher);
                                buffer.position = pos + 2 + size;
                            }
                            break;
                    }
                }
            }
        } catch (Exception e) {
            error(e);
            return false;
        }
        if (buffer.position != dataEnd) {
            error(new Exception(PARSE_DATA_FAILED));
            return false;
        }
        needRewrite = !hadEncrypted && cipher != null && dataEnd != DATA_START;
        return true;
    }

    private void parseObject(int size, String key, int start, int pos,
                             FastCipher dataCipher) throws Exception {
        FastBuffer buffer;
        int dataLen;
        if (dataCipher == null) {
            buffer = fastBuffer;
            dataLen = size;
        } else {
            byte[] bytes = new byte[size];
            System.arraycopy(fastBuffer.hb, fastBuffer.position, bytes, 0, size);
            byte[] dstBytes = dataCipher.decrypt(bytes);
            buffer = new FastBuffer(dstBytes);
            dataLen = dstBytes.length;
        }
        int tagSize = buffer.get() & 0xFF;
        String tag = buffer.getString(tagSize);
        FastEncoder encoder = encoderMap.get(tag);
        int objectSize = dataLen - (tagSize + 1);
        if (objectSize < 0) {
            throw new Exception(PARSE_DATA_FAILED);
        }
        if (encoder != null) {
            try {
                Object obj = encoder.decode(buffer.hb, buffer.position, objectSize);
                if (obj != null) {
                    ObjectContainer container = new ObjectContainer(start, pos + 2, obj, size, false);
                    container.encoder = encoder;
                    data.put(key, container);
                }
            } catch (Exception e) {
                error(e);
            }
        } else {
            error("object with tag: " + tag + " without encoder");
        }
    }

    protected final void checkKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key is empty");
        }
    }

    protected final void checkKeySize(int keySize) {
        if (keySize > 0xFF) {
            throw new IllegalArgumentException("key's length must less than 256");
        }
    }

    protected static class Segment implements Comparable<Segment> {
        int start;
        int end;

        Segment(int start, int end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public int compareTo(Segment o) {
            return start - o.start;
        }
    }

    protected final void mergeInvalids() {
        int i = invalids.size() - 1;
        Segment p = invalids.get(i);
        while (i > 0) {
            Segment q = invalids.get(--i);
            if (p.start == q.end) {
                q.end = p.end;
                invalids.remove(i + 1);
            }
            p = q;
        }
    }

    protected final void updateOffset(int gcStart, int[] srcToShift) {
        Collection<BaseContainer> values = data.values();
        for (BaseContainer c : values) {
            if (c.offset > gcStart) {
                int index = Util.binarySearch(srcToShift, c.offset);
                int shift = srcToShift[(index << 1) + 1];
                c.offset -= shift;
                if (c.getType() >= DataType.STRING) {
                    ((VarContainer) c).start -= shift;
                }
            }
        }
    }

    protected final void tryBlockingIO(File aFile, File bFile) {
        try {
            if (loadWithBlockingIO(aFile)) {
                return;
            }
        } catch (IOException e) {
            warning(e);
        }
        resetMemory();
        try {
            if (loadWithBlockingIO(bFile)) {
                return;
            }
        } catch (IOException e) {
            warning(e);
        }
        resetMemory();
    }

    protected final void resetMemory() {
        resetData();
        resetBuffer();
    }

    protected void resetData() {
        dataEnd = DATA_START;
        checksum = 0L;
        data.clear();
        bigValueCache.clear();
        externalCache.clear();
        clearInvalid();
    }

    private void resetBuffer() {
        if (fastBuffer == null || fastBuffer.hb.length != PAGE_SIZE) {
            fastBuffer = new FastBuffer(PAGE_SIZE);
        } else {
            fastBuffer.putLong(4, 0L);
        }
        fastBuffer.putInt(0, packSize(0));
    }

    protected final void countInvalid(int start, int end) {
        invalidBytes += (end - start);
        invalids.add(new Segment(start, end));
    }

    protected final void clearInvalid() {
        invalidBytes = 0;
        invalids.clear();
    }

    protected final void error(String message) {
        if (logger != null) {
            logger.e(name, new Exception(message));
        }
    }

    protected final void error(Throwable t) {
        if (logger != null) {
            logger.e(name, new Exception(t));
        }
    }

    protected final void error(Exception e) {
        if (logger != null) {
            logger.e(name, e);
        }
    }

    protected final void warning(Exception e) {
        if (logger != null) {
            logger.w(name, e);
        }
    }

    protected final void info(String message) {
        if (logger != null) {
            logger.i(name, message);
        }
    }

    protected final int bytesThreshold() {
        if (dataEnd <= (1 << 14)) {
            return BASE_GC_BYTES_THRESHOLD;
        } else {
            return dataEnd <= (1 << 16) ? BASE_GC_BYTES_THRESHOLD << 1 : BASE_GC_BYTES_THRESHOLD << 2;
        }
    }

    protected abstract void checkGC();

    protected final long shiftCheckSum(long checkSum, int offset) {
        int shift = (offset & 7) << 3;
        return (checkSum << shift) | (checkSum >>> (64 - shift));
    }

    public synchronized boolean contains(String key) {
        return data.containsKey(key);
    }

    public synchronized boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public synchronized boolean getBoolean(String key, boolean defValue) {
        BooleanContainer c = (BooleanContainer) data.get(key);
        return c == null ? defValue : c.value;
    }

    public int getInt(String key) {
        return getInt(key, 0);
    }

    public synchronized int getInt(String key, int defValue) {
        IntContainer c = (IntContainer) data.get(key);
        return c == null ? defValue : c.value;
    }

    public float getFloat(String key) {
        return getFloat(key, 0f);
    }

    public synchronized float getFloat(String key, float defValue) {
        FloatContainer c = (FloatContainer) data.get(key);
        return c == null ? defValue : c.value;
    }

    public synchronized long getLong(String key) {
        LongContainer c = (LongContainer) data.get(key);
        return c == null ? 0L : c.value;
    }

    public synchronized long getLong(String key, long defValue) {
        LongContainer c = (LongContainer) data.get(key);
        return c == null ? defValue : c.value;
    }

    public double getDouble(String key) {
        return getDouble(key, 0D);
    }

    public synchronized double getDouble(String key, double defValue) {
        DoubleContainer c = (DoubleContainer) data.get(key);
        return c == null ? defValue : c.value;
    }

    public String getString(String key) {
        return getString(key, "");
    }

    public synchronized String getString(String key, String defValue) {
        StringContainer c = (StringContainer) data.get(key);
        if (c != null) {
            if (c.external) {
                Object value = bigValueCache.get(key);
                if (value instanceof String) {
                    return (String) value;
                }
                String str = getStringFromFile(c, cipher);
                if (str == null || str.isEmpty()) {
                    removeKey(key);
                } else {
                    bigValueCache.put(key, str);
                    return str;
                }
            } else {
                return (String) c.value;
            }
        }
        return defValue;
    }

    protected String getStringFromFile(StringContainer c, FastCipher fastCipher) {
        String fileName = (String) c.value;
        byte[] cache = (byte[]) externalCache.get(fileName);
        try {
            byte[] bytes = (cache != null) ? cache : Util.getBytes(new File(path + name, fileName));
            if (bytes != null) {
                bytes = fastCipher != null ? fastCipher.decrypt(bytes) : bytes;
                return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
            }
        } catch (Throwable e) {
            error(e);
        }
        return null;
    }

    public byte[] getArray(String key) {
        return getArray(key, EMPTY_ARRAY);
    }

    public synchronized byte[] getArray(String key, byte[] defValue) {
        ArrayContainer c = (ArrayContainer) data.get(key);
        if (c != null) {
            if (c.external) {
                Object value = bigValueCache.get(key);
                if (value instanceof byte[]) {
                    return (byte[]) value;
                }
                byte[] bytes = getArrayFromFile(c, cipher);
                if (bytes == null || bytes.length == 0) {
                    removeKey(key);
                } else {
                    bigValueCache.put(key, bytes);
                    return bytes;
                }
            } else {
                return (byte[]) c.value;
            }
        }
        return defValue;
    }

    protected byte[] getArrayFromFile(ArrayContainer c, FastCipher fastCipher) {
        String fileName = (String) c.value;
        byte[] cache = (byte[]) externalCache.get(fileName);
        try {
            byte[] bytes = cache != null ? cache : Util.getBytes(new File(path + name, fileName));
            if (bytes != null) {
                return fastCipher != null ? fastCipher.decrypt(bytes) : bytes;
            }
        } catch (Throwable e) {
            error(e);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> T getObject(String key) {
        ObjectContainer c = (ObjectContainer) data.get(key);
        if (c != null) {
            if (c.external) {
                Object value = bigValueCache.get(key);
                if (value != null) {
                    return (T) value;
                }
                Object obj = getObjectFromFile(c, cipher);
                if (obj == null) {
                    removeKey(key);
                } else {
                    bigValueCache.put(key, obj);
                    return (T) obj;
                }
            } else {
                return (T) c.value;
            }
        }
        return null;
    }

    protected Object getObjectFromFile(ObjectContainer c, FastCipher fastCipher) {
        String fileName = (String) c.value;
        byte[] cache = (byte[]) externalCache.get(fileName);
        try {
            byte[] bytes = cache != null ? cache : Util.getBytes(new File(path + name, fileName));
            if (bytes != null) {
                bytes = fastCipher != null ? fastCipher.decrypt(bytes) : bytes;
                int tagSize = bytes[0] & 0xFF;
                String tag = fastBuffer.decodeStr(bytes, 1, tagSize);
                FastEncoder encoder = encoderMap.get(tag);
                if (encoder != null) {
                    c.encoder = encoder;
                    int offset = 1 + tagSize;
                    return encoder.decode(bytes, offset, bytes.length - offset);
                } else {
                    warning(new Exception("No encoder for tag:" + tag));
                }
            } else {
                warning(new Exception("Read object data failed"));
            }
        } catch (Throwable e) {
            error(e);
        }
        return null;
    }

    public synchronized Set<String> getStringSet(String key) {
        return getObject(key);
    }

    public synchronized Map<String, Object> getAll() {
        int size = data.size();
        if (size == 0) {
            return new HashMap<>();
        }
        Map<String, Object> result = new HashMap<>(size * 4 / 3 + 1);
        for (Map.Entry<String, BaseContainer> entry : data.entrySet()) {
            String key = entry.getKey();
            BaseContainer c = entry.getValue();
            Object value = null;
            switch (c.getType()) {
                case DataType.BOOLEAN:
                    value = ((BooleanContainer) c).value;
                    break;
                case DataType.INT:
                    value = ((IntContainer) c).value;
                    break;
                case DataType.FLOAT:
                    value = ((FloatContainer) c).value;
                    break;
                case DataType.LONG:
                    value = ((LongContainer) c).value;
                    break;
                case DataType.DOUBLE:
                    value = ((DoubleContainer) c).value;
                    break;
                case DataType.STRING:
                    StringContainer sc = (StringContainer) c;
                    value = sc.external ? getStringFromFile(sc, cipher) : sc.value;
                    break;
                case DataType.ARRAY:
                    ArrayContainer ac = (ArrayContainer) c;
                    value = ac.external ? getArrayFromFile(ac, cipher) : ac.value;
                    break;
                case DataType.OBJECT:
                    ObjectContainer oc = (ObjectContainer) c;
                    value = oc.external ? getObjectFromFile(oc, cipher) : ((ObjectContainer) c).value;
                    break;
            }
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * Batch put objects.
     * Only support type in [boolean, int, long, float, double, String, byte[], Set of String] and object with encoder.
     *
     * @param values   map of key to value
     * @param encoders map of value Class to Encoder
     */
    public synchronized void putAll(Map<String, Object> values, Map<Class, FastEncoder> encoders) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key != null && !key.isEmpty()) {
                if (value instanceof String) {
                    putStringValue(key, (String) value);
                } else if (value instanceof Boolean) {
                    putBooleanValue(key, (Boolean) value);
                } else if (value instanceof Integer) {
                    putIntValue(key, (Integer) value);
                } else if (value instanceof Long) {
                    putLongValue(key, (Long) value);
                } else if (value instanceof Float) {
                    putFloatValue(key, (Float) value);
                } else if (value instanceof Double) {
                    putDoubleValue(key, (Double) value);
                } else if (value instanceof byte[]) {
                    putArrayValue(key, (byte[]) value);
                } else {
                    encodeObject(key, value, encoders);
                }
            }
        }
    }

    private void encodeObject(String key, Object value, Map<Class, FastEncoder> encoders) {
        if (value instanceof Set) {
            Set set = (Set) value;
            if (set.isEmpty() || set.iterator().next() instanceof String) {
                //noinspection unchecked
                putStringSetValue(key, set);
                return;
            }
        }
        if (encoders != null) {
            FastEncoder encoder = encoders.get(value.getClass());
            if (encoder != null) {
                //noinspection unchecked
                putObjectValue(key, value, encoder);
            } else {
                warning(new Exception("missing encoder for type:" + value.getClass()));
            }
        } else {
            warning(new Exception("missing encoders"));
        }
    }

    protected abstract void ensureSize(int allocate);

    protected void preparePutBytes() {
        ensureSize(updateSize);
        updateStart = dataEnd;
        dataEnd += updateSize;
        fastBuffer.position = updateStart;
    }

    protected void wrapHeader(String key, byte type) {
        wrapHeader(key, type, TYPE_SIZE[type]);
    }

    protected void wrapHeader(String key, byte type, int valueSize) {
        if (cipher != null) {
            byte[] keyBytes = cipher.encrypt(key.getBytes(StandardCharsets.UTF_8));
            int keySize = keyBytes.length;
            prepareHeaderInfo(keySize, valueSize, type);
            fastBuffer.put((byte) keySize);
            System.arraycopy(keyBytes, 0, fastBuffer.hb, fastBuffer.position, keySize);
            fastBuffer.position += keySize;
        } else {
            int keySize = FastBuffer.getStringSize(key);
            prepareHeaderInfo(keySize, valueSize, type);
            wrapKey(key, keySize);
        }
    }

    protected void prepareHeaderInfo(int keySize, int valueSize, byte type) {
        checkKeySize(keySize);
        updateSize = 2 + keySize + valueSize;
        preparePutBytes();
        fastBuffer.put(type);
    }

    private void wrapKey(String key, int keySize) {
        fastBuffer.put((byte) keySize);
        if (keySize == key.length()) {
            //noinspection deprecation
            key.getBytes(0, keySize, fastBuffer.hb, fastBuffer.position);
            fastBuffer.position += keySize;
        } else {
            fastBuffer.putString(key);
        }
    }

    protected void remove(byte type, int start, int end) {
        countInvalid(start, end);
        byte newByte = (byte) (type | DataType.DELETE_MASK);
        byte oldByte = fastBuffer.hb[start];
        int shift = (start & 7) << 3;
        checksum ^= ((long) (newByte ^ oldByte) & 0xFF) << shift;
        fastBuffer.hb[start] = newByte;
    }

    protected void lockAndCheckUpdate() {
        // prepare for MPFastKV
    }

    protected abstract void updateChange();

    protected abstract void handleChange(String key);

    protected abstract void removeKey(String key);

    protected abstract void updateBoolean(byte value, int offset);

    protected abstract void updateInt32(int value, long sum, int offset);

    protected abstract void updateInt64(long value, long sum, int offset);

    protected void updateBytes(int offset, byte[] bytes) {
        int size = bytes.length;
        checksum ^= fastBuffer.getChecksum(offset, size);
        fastBuffer.position = offset;
        fastBuffer.putBytes(bytes);
        checksum ^= fastBuffer.getChecksum(offset, size);
    }

    protected void putBooleanValue(String key, boolean value) {
        checkKey(key);
        lockAndCheckUpdate();
        BooleanContainer c = (BooleanContainer) data.get(key);
        if (c == null) {
            wrapHeader(key, DataType.BOOLEAN);
            int offset = fastBuffer.position;
            fastBuffer.put((byte) (value ? 1 : 0));
            updateChange();
            data.put(key, new BooleanContainer(offset, value));
            handleChange(key);
        } else if (c.value != value) {
            c.value = value;
            updateBoolean((byte) (value ? 1 : 0), c.offset);
            handleChange(key);
        }
    }

    protected void putIntValue(String key, int value) {
        checkKey(key);
        lockAndCheckUpdate();
        IntContainer c = (IntContainer) data.get(key);
        if (c == null) {
            wrapHeader(key, DataType.INT);
            int offset = fastBuffer.position;
            fastBuffer.putInt(cipher != null ? cipher.encrypt(value) : value);
            updateChange();
            data.put(key, new IntContainer(offset, value));
            handleChange(key);
        } else if (c.value != value) {
            int newValue = cipher != null ? cipher.encrypt(value) : value;
            int oldValue = cipher != null ? fastBuffer.getInt(c.offset) : c.value;
            long sum = (newValue ^ oldValue) & 0xFFFFFFFFL;
            c.value = value;
            updateInt32(newValue, sum, c.offset);
            handleChange(key);
        }
    }

    protected void putFloatValue(String key, float value) {
        checkKey(key);
        lockAndCheckUpdate();
        FloatContainer c = (FloatContainer) data.get(key);
        if (c == null) {
            wrapHeader(key, DataType.FLOAT);
            int offset = fastBuffer.position;
            fastBuffer.putInt(getNewFloatValue(value));
            updateChange();
            data.put(key, new FloatContainer(offset, value));
            handleChange(key);
        } else if (c.value != value) {
            int newValue = getNewFloatValue(value);
            int oldValue = fastBuffer.getInt(c.offset);
            long sum = (newValue ^ oldValue) & 0xFFFFFFFFL;
            c.value = value;
            updateInt32(newValue, sum, c.offset);
            handleChange(key);
        }
    }

    private int getNewFloatValue(float value) {
        int intValue = Float.floatToRawIntBits(value);
        return cipher != null ? cipher.encrypt(intValue) : intValue;
    }

    protected void putLongValue(String key, long value) {
        checkKey(key);
        lockAndCheckUpdate();
        LongContainer c = (LongContainer) data.get(key);
        if (c == null) {
            wrapHeader(key, DataType.LONG);
            int offset = fastBuffer.position;
            fastBuffer.putLong(cipher != null ? cipher.encrypt(value) : value);
            updateChange();
            data.put(key, new LongContainer(offset, value));
            handleChange(key);
        } else if (c.value != value) {
            long newValue = cipher != null ? cipher.encrypt(value) : value;
            long oldValue = cipher != null ? fastBuffer.getLong(c.offset) : c.value;
            long sum = newValue ^ oldValue;
            c.value = value;
            updateInt64(newValue, sum, c.offset);
            handleChange(key);
        }
    }

    protected void putDoubleValue(String key, double value) {
        checkKey(key);
        lockAndCheckUpdate();
        DoubleContainer c = (DoubleContainer) data.get(key);
        if (c == null) {
            wrapHeader(key, DataType.DOUBLE);
            int offset = fastBuffer.position;
            fastBuffer.putLong(getNewDoubleValue(value));
            updateChange();
            data.put(key, new DoubleContainer(offset, value));
            handleChange(key);
        } else if (c.value != value) {
            long newValue = getNewDoubleValue(value);
            long oldValue = fastBuffer.getLong(c.offset);
            long sum = newValue ^ oldValue;
            c.value = value;
            updateInt64(newValue, sum, c.offset);
            handleChange(key);
        }
    }

    private long getNewDoubleValue(double value) {
        long longValue = Double.doubleToRawLongBits(value);
        return cipher != null ? cipher.encrypt(longValue) : longValue;
    }

    protected void putStringValue(String key, String value) {
        checkKey(key);
        if (value == null) {
            removeKey(key);
        } else {
            lockAndCheckUpdate();
            StringContainer c = (StringContainer) data.get(key);
            if (cipher == null && value.length() * 3 < INTERNAL_LIMIT) {
                // 'putString' is a frequent operation,
                // so we make some strategies to speed up 'putString'.
                fastPutString(key, value, c);
            } else {
                byte[] bytes = value.isEmpty() ? EMPTY_ARRAY : value.getBytes(StandardCharsets.UTF_8);
                byte[] newBytes = cipher != null ? cipher.encrypt(bytes) : bytes;
                addOrUpdate(key, value, newBytes, c, DataType.STRING);
            }
            handleChange(key);
        }
    }

    /**
     * If length of string is equal in UFT-8 and UTF-16,
     * it can use 'getBytes(int srcBegin, int srcEnd, byte dst[], int dstBegin)' to wrap the data,
     * which is faster then 'getBytes(String charsetName)'
     */
    protected void fastPutString(String key, String value, StringContainer c) {
        int stringLen = FastBuffer.getStringSize(value);
        if (c == null) {
            int keyLen = FastBuffer.getStringSize(key);
            checkKeySize(keyLen);
            // 4 bytes = type:1, keyLen: 1, stringLen:2
            // preSize include size of [type|keyLen|key|stringLen], which is "4+lengthOf(key)"
            int preSize = 4 + keyLen;
            updateSize = preSize + stringLen;
            preparePutBytes();
            fastBuffer.put(DataType.STRING);
            wrapKey(key, keyLen);
            wrapStringValue(value, stringLen);
            data.put(key, new StringContainer(updateStart, updateStart + preSize, value, stringLen, false));
            updateChange();
        } else {
            final String oldFileName;
            boolean needCheckGC = false;
            // preSize: bytes count from start to value offset
            int preSize = c.offset - c.start;
            if (c.valueSize == stringLen) {
                checksum ^= fastBuffer.getChecksum(c.offset, c.valueSize);
                if (stringLen == value.length()) {
                    //noinspection deprecation
                    value.getBytes(0, stringLen, fastBuffer.hb, c.offset);
                } else {
                    fastBuffer.position = c.offset;
                    fastBuffer.putString(value);
                }
                updateStart = c.offset;
                updateSize = stringLen;
                oldFileName = null;
            } else {
                updateSize = preSize + stringLen;
                preparePutBytes();
                fastBuffer.put(DataType.STRING);
                int keyBytes = preSize - 3;
                System.arraycopy(fastBuffer.hb, c.start + 1, fastBuffer.hb, fastBuffer.position, keyBytes);
                fastBuffer.position += keyBytes;
                wrapStringValue(value, stringLen);

                remove(DataType.STRING, c.start, c.offset + c.valueSize);
                needCheckGC = true;
                oldFileName = c.external ? (String) c.value : null;

                c.external = false;
                c.start = updateStart;
                c.offset = updateStart + preSize;
                c.valueSize = stringLen;
            }
            c.value = value;
            updateChange();
            if (needCheckGC) {
                checkGC();
            }
            if (oldFileName != null) {
                removeOldFile(oldFileName);
            }
        }
    }

    protected void putArrayValue(String key, byte[] value) {
        checkKey(key);
        if (value == null) {
            removeKey(key);
        } else {
            lockAndCheckUpdate();
            ArrayContainer c = (ArrayContainer) data.get(key);
            byte[] newBytes = cipher != null ? cipher.encrypt(value) : value;
            addOrUpdate(key, value, newBytes, c, DataType.ARRAY);
            handleChange(key);
        }
    }

    protected <T> void putObjectValue(String key, T value, FastEncoder<T> encoder) {
        checkKey(key);
        if (encoder == null) {
            throw new IllegalArgumentException("Encoder is null");
        }
        String tag = encoder.tag();
        if (tag == null || tag.isEmpty() || tag.length() > 50) {
            throw new IllegalArgumentException("Invalid encoder tag:" + tag);
        }
        if (!encoderMap.containsKey(tag)) {
            throw new IllegalArgumentException("Encoder hasn't been registered");
        }

        if (value == null) {
            removeKey(key);
            return;
        }
        byte[] objBytes = null;
        try {
            objBytes = encoder.encode(value);
        } catch (Exception e) {
            error(e);
        }
        if (objBytes == null) {
            removeKey(key);
            return;
        }

        // assemble object bytes
        int tagSize = FastBuffer.getStringSize(tag);
        FastBuffer buffer = new FastBuffer(1 + tagSize + objBytes.length);
        buffer.put((byte) tagSize);
        buffer.putString(tag);
        buffer.putBytes(objBytes);
        byte[] bytes = buffer.hb;

        lockAndCheckUpdate();
        ObjectContainer c = (ObjectContainer) data.get(key);
        byte[] newBytes = cipher != null ? cipher.encrypt(bytes) : bytes;
        addOrUpdate(key, value, newBytes, c, DataType.OBJECT);
        handleChange(key);
    }

    protected void putStringSetValue(String key, Set<String> set) {
        if (set == null) {
            removeKey(key);
        } else {
            putObjectValue(key, set, StringSetEncoder.INSTANCE);
        }
    }

    protected abstract void removeOldFile(String oldFileName);

    private void wrapStringValue(String value, int valueSize) {
        fastBuffer.putShort((short) valueSize);
        if (valueSize == value.length()) {
            //noinspection deprecation
            value.getBytes(0, valueSize, fastBuffer.hb, fastBuffer.position);
        } else {
            fastBuffer.putString(value);
        }
    }

    protected void addOrUpdate(String key, Object value, byte[] bytes, VarContainer c, byte type) {
        if (c == null) {
            addObject(key, value, bytes, type);
        } else {
            if (!c.external && c.valueSize == bytes.length) {
                updateBytes(c.offset, bytes);
                c.value = value;
            } else {
                updateObject(key, value, bytes, c);
            }
        }
    }

    private void addObject(String key, Object value, byte[] bytes, byte type) {
        int offset = saveArray(key, bytes, type);
        if (offset != 0) {
            int size;
            Object v;
            boolean external = tempExternalName != null;
            if (external) {
                bigValueCache.put(key, value);
                size = Util.NAME_SIZE;
                v = tempExternalName;
                tempExternalName = null;
            } else {
                size = bytes.length;
                v = value;
            }
            BaseContainer c;
            if (type == DataType.STRING) {
                c = new StringContainer(updateStart, offset, (String) v, size, external);
            } else if (type == DataType.ARRAY) {
                c = new ArrayContainer(updateStart, offset, v, size, external);
            } else {
                c = new ObjectContainer(updateStart, offset, v, size, external);
            }
            data.put(key, c);
            updateChange();
        }
    }

    private void updateObject(String key, Object value, byte[] bytes, VarContainer c) {
        int offset = saveArray(key, bytes, c.getType());
        if (offset != 0) {
            String oldFileName = c.external ? (String) c.value : null;
            remove(c.getType(), c.start, c.offset + c.valueSize);
            boolean external = tempExternalName != null;
            c.start = updateStart;
            c.offset = offset;
            c.external = external;
            if (external) {
                bigValueCache.put(key, value);
                c.value = tempExternalName;
                c.valueSize = Util.NAME_SIZE;
                tempExternalName = null;
            } else {
                c.value = value;
                c.valueSize = bytes.length;
            }
            updateChange();
            checkGC();
            if (oldFileName != null) {
                removeOldFile(oldFileName);
            }
        }
    }

    private int saveArray(String key, byte[] value, byte type) {
        tempExternalName = null;
        if (value.length < INTERNAL_LIMIT) {
            return wrapArray(key, value, type);
        } else {
            info("Large value, key: " + key + ", size: " + value.length);
            String fileName = Util.randomName();

            // The reference of 'value' will not be gc before 'saveBytes' finish,
            // So before the value saving to disk, it could be read with 'externalCache'.
            externalCache.put(fileName, value);

            // In fact, there is a small probability of causing consistency issues
            // by saving bytes asynchronously,
            // But we have to save it asynchronously for efficiency.
            externalExecutor.execute(key, () -> {
                long startTime = System.nanoTime();
                if (!Util.saveBytes(new File(path + name, fileName), value)) {
                    info("Write large value with key:" + key + " failed");
                } else {
                    long t = System.nanoTime() - startTime;
                    String formatTime = (t / 1000000) + "." + ((t % 1000000) / 10000);
                    info("Write large value with key:" + key + ", use time:" + formatTime + " ms");
                }
            });

            tempExternalName = fileName;
            byte[] fileNameBytes = new byte[Util.NAME_SIZE];
            //noinspection deprecation
            fileName.getBytes(0, Util.NAME_SIZE, fileNameBytes, 0);
            return wrapArray(key, fileNameBytes, (byte) (type | DataType.EXTERNAL_MASK));
        }
    }

    private int wrapArray(String key, byte[] value, byte type) {
        wrapHeader(key, type, 2 + value.length);
        fastBuffer.putShort((short) value.length);
        int offset = fastBuffer.position;
        fastBuffer.putBytes(value);
        return offset;
    }
}
