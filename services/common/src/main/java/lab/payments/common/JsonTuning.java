package lab.payments.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** Applies F-09 (lab.tuning.f09) to the static JSON and logging helpers at startup. */
@Configuration
public class JsonTuning {

    public JsonTuning(@Value("${lab.tuning.f09:false}") boolean lean) {
        Json.useSharedMapper(lean);
        EventLog.verbose(!lean);
    }
}
