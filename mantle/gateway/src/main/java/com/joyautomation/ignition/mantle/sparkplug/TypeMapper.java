package com.joyautomation.ignition.mantle.sparkplug;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import com.inductiveautomation.ignition.common.BasicDataset;
import com.inductiveautomation.ignition.common.Dataset;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;
import org.eclipse.tahu.message.model.DataSet;
import org.eclipse.tahu.message.model.DataSetDataType;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.Row;
import org.eclipse.tahu.message.model.Value;

/**
 * Sparkplug B datatypes to Ignition tag datatypes, and back again for writes.
 * <p>
 * Ignition has no unsigned types, so unsigned Sparkplug types widen to the next signed type rather than wrap.
 * UInt64 is the one that can't widen; it saturates at Long.MAX_VALUE.
 */
public final class TypeMapper {
    private TypeMapper() {
    }

    // Sparkplug B datatype codes, from the spec. Tahu models these as instances rather than an enum, so they
    // can't be switched on directly.
    private static final int INT8 = 1, INT16 = 2, INT32 = 3, INT64 = 4, UINT8 = 5, UINT16 = 6, UINT32 = 7,
        UINT64 = 8, FLOAT = 9, DOUBLE = 10, BOOLEAN = 11, STRING = 12, DATETIME = 13, TEXT = 14, UUID = 15,
        DATASET = 16, BYTES = 17, FILE = 18, INT8_ARRAY = 22, INT16_ARRAY = 23, INT32_ARRAY = 24,
        INT64_ARRAY = 25, UINT8_ARRAY = 26, UINT16_ARRAY = 27, UINT32_ARRAY = 28, UINT64_ARRAY = 29,
        FLOAT_ARRAY = 30, DOUBLE_ARRAY = 31, BOOLEAN_ARRAY = 32, STRING_ARRAY = 33, DATETIME_ARRAY = 34;

    public static boolean isTemplate(MetricDataType type) {
        return MetricDataType.Template.equals(type);
    }

    /** @return the Ignition type for a metric, or null when the metric can't be represented as a single tag. */
    public static DataType toIgnition(MetricDataType type) {
        if (type == null) {
            return null;
        }
        return switch (type.toIntValue()) {
            case INT8 -> DataType.Int1;
            case INT16, UINT8 -> DataType.Int2;
            case INT32, UINT16 -> DataType.Int4;
            case INT64, UINT32, UINT64 -> DataType.Int8;
            case FLOAT -> DataType.Float4;
            case DOUBLE -> DataType.Float8;
            case BOOLEAN -> DataType.Boolean;
            case STRING, UUID -> DataType.String;
            case TEXT -> DataType.Text;
            case DATETIME -> DataType.DateTime;
            case DATASET -> DataType.DataSet;
            case BYTES, FILE -> DataType.ByteArray;
            case INT8_ARRAY -> DataType.Int1Array;
            case INT16_ARRAY, UINT8_ARRAY -> DataType.Int2Array;
            case INT32_ARRAY, UINT16_ARRAY -> DataType.Int4Array;
            case INT64_ARRAY, UINT32_ARRAY, UINT64_ARRAY -> DataType.Int8Array;
            case FLOAT_ARRAY -> DataType.Float4Array;
            case DOUBLE_ARRAY -> DataType.Float8Array;
            case BOOLEAN_ARRAY -> DataType.BooleanArray;
            case STRING_ARRAY -> DataType.StringArray;
            case DATETIME_ARRAY -> DataType.DateTimeArray;
            // Template is expanded into a folder of member tags by the caller. Unknown has no mapping.
            default -> null;
        };
    }

    /** Converts a decoded Sparkplug metric value into something the tag system will accept for {@code type}. */
    public static Object toIgnitionValue(MetricDataType type, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigInteger big) {
            return saturate(big);
        }
        if (value instanceof BigInteger[] bigs) {
            Long[] out = new Long[bigs.length];
            for (int i = 0; i < bigs.length; i++) {
                out[i] = bigs[i] == null ? null : saturate(bigs[i]);
            }
            return out;
        }
        if (value instanceof DataSet ds) {
            return toIgnitionDataset(ds);
        }
        if (value instanceof org.eclipse.tahu.message.model.File file) {
            return file.getBytes();
        }
        return value;
    }

    /** Coerces a value written to a tag into the Java type Tahu expects when encoding a metric of {@code type}. */
    public static Object toSparkplugValue(MetricDataType type, Object value) {
        if (value == null) {
            return null;
        }
        return switch (type.toIntValue()) {
            case INT8 -> number(value).byteValue();
            case INT16, UINT8 -> number(value).shortValue();
            case INT32, UINT16 -> number(value).intValue();
            case INT64, UINT32 -> number(value).longValue();
            case UINT64 -> BigInteger.valueOf(number(value).longValue());
            case FLOAT -> number(value).floatValue();
            case DOUBLE -> number(value).doubleValue();
            case BOOLEAN -> value instanceof Boolean b ? b : number(value).doubleValue() != 0;
            case STRING, TEXT, UUID -> value.toString();
            default -> value;
        };
    }

    private static Number number(Object value) {
        if (value instanceof Number n) {
            return n;
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return Double.valueOf(value.toString());
    }

    private static long saturate(BigInteger big) {
        return big.bitLength() > 63 ? Long.MAX_VALUE : big.longValue();
    }

    private static Dataset toIgnitionDataset(DataSet ds) {
        List<String> names = ds.getColumnNames();
        List<DataSetDataType> types = ds.getTypes();
        List<Class<?>> columnTypes = new ArrayList<>(types.size());
        for (DataSetDataType t : types) {
            columnTypes.add(columnClass(t));
        }
        List<Row> rows = ds.getRows();
        Object[][] data = new Object[names.size()][rows.size()];
        for (int r = 0; r < rows.size(); r++) {
            List<Value<?>> values = rows.get(r).getValues();
            for (int c = 0; c < names.size() && c < values.size(); c++) {
                Object v = values.get(c).getValue();
                data[c][r] = v instanceof BigInteger big ? saturate(big) : v;
            }
        }
        return new BasicDataset(names, columnTypes, data);
    }

    private static Class<?> columnClass(DataSetDataType type) {
        Class<?> clazz = type.getClazz();
        // unsigned 64-bit columns decode to BigInteger and are saturated to Long on the way in
        return clazz == null ? String.class : clazz == BigInteger.class ? Long.class : clazz;
    }
}
