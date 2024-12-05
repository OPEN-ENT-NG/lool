package fr.openent.lool.helper;

import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class DateHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(DateHelper.class);

    public static final String  ISO8601_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";
    public static final String MONGO_DATE_FORMAT = "yyyy-MM-dd HH:mm.ss";


    private DateHelper() {
        throw new IllegalStateException("Utility class");
    }

    public static Date parse(String date, String format) throws ParseException {
        SimpleDateFormat sdf = new SimpleDateFormat(format);
        return sdf.parse(date);
    }

    /**
     * Get Simple date as string
     *
     * @param date              date to format
     * @param formattedDate     format of the first param date you send
     * @param format            the format wished
     * @return Simple date format as string
     */
    public static String getDateString(String date, String formattedDate, String format) {
        try {
            Date parsedDate = parse(date, formattedDate);
            return new SimpleDateFormat(format).format(parsedDate);
        } catch (ParseException err) {
            LOGGER.error("[Lool@DateHelper::getDateString] Failed to parse date " + date, err);
            return date;
        }
    }
}
