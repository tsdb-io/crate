package io.crate.expression.scalar.timestamp;

import io.crate.expression.scalar.AbstractScalarFunctionsTest;
import io.crate.expression.scalar.DateFormatFunction;
import io.crate.expression.scalar.TimestampFormatter;
import io.crate.expression.symbol.Literal;
import io.crate.types.TimestampType;
import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.instanceOf;

public class CurrentTimeFunctionTest extends AbstractScalarFunctionsTest {

    private static final long TIMESTAMP = 257508552123L; // "1978-02-28T10:09:12.123456Z"
    private static final long TIME = 36552123L;
    private static final String TIME_AS_TIMESTAMP_STR = "1970-01-01T10:09:12.123000Z";

    private static String formatTs(long ts) {
        return TimestampFormatter.format(
            DateFormatFunction.DEFAULT_FORMAT,
            new DateTime(
                TimestampType.INSTANCE_WITH_TZ.value(ts),
                DateTimeZone.UTC));
    }

    @Before
    public void prepare() {
        DateTimeUtils.setCurrentMillisFixed(TIMESTAMP);
    }

    @After
    public void cleanUp() {
        DateTimeUtils.setCurrentMillisSystem();
    }

    @Test
    public void timestampIsCreatedCorrectly() {
        assertEvaluate("current_time", TIME);
        assertEvaluate("date_format(current_time)", formatTs(TIME));
        assertEvaluate("current_timestamp - current_time", TIMESTAMP - TIME);
        assertEvaluate("date_format(current_timestamp - current_time)", formatTs(TIMESTAMP - TIME));
    }

    @Test
    public void precisionOfZeroDropsAllFractionsOfSeconds() {
        assertEvaluate("current_time(0)", TIME - (TIME % 1000));
    }

    @Test
    public void precisionOfOneDropsLastTwoDigitsOfFractionsOfSecond() {
        assertEvaluate("current_time(1)", TIME - (TIME % 100));
    }

    @Test
    public void precisionOfTwoDropsLastDigitOfFractionsOfSecond() {
        assertEvaluate("current_time(2)", TIME - (TIME % 10));
    }

    @Test
    public void precisionOfThreeKeepsAllFractionsOfSeconds() {
        assertEvaluate("date_format(current_time(3))", TIME_AS_TIMESTAMP_STR);
    }

    @Test
    public void precisionLargerThan3RaisesException() {
        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage("Precision must be between 0 and 3");
        assertEvaluate("current_time(4)", null);
    }

    @Test
    public void integerIsNormalizedToLiteral() {
        assertNormalize("current_time(1)", instanceOf(Literal.class));
    }
}
