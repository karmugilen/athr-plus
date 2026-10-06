use jni::{objects::{JClass, JDoubleArray, JLongArray}, sys::{jdouble, jdoubleArray, jintArray, jlong}, JNIEnv};

// Returns indices into the original measurements, never interpolated battery values.
fn history_indices(times: &[i64], soc: &[f64], start: i64, end: i64) -> Vec<i32> {
    let mut indices: Vec<usize> = times.iter().enumerate().filter_map(|(i, &t)| {
        let value = *soc.get(i)?;
        (t > 0 && t >= start && t <= end && value.is_finite() && (0.0..=100.0).contains(&value)).then_some(i)
    }).collect();
    indices.sort_by_key(|&i| times[i]);
    indices.dedup_by_key(|i| times[*i]);
    indices.into_iter().map(|i| i as i32).collect()
}

#[no_mangle]
pub extern "system" fn Java_io_ather_pro_data_computation_RustTelemetryMath_historyIndices(
    mut env: JNIEnv, _class: JClass, timestamps: JLongArray, values: JDoubleArray, start: jlong, end: jlong,
) -> jintArray {
    let result = (|| -> jni::errors::Result<jintArray> {
        let mut times = vec![0; env.get_array_length(&timestamps)? as usize];
        let mut soc = vec![0.0; env.get_array_length(&values)? as usize];
        env.get_long_array_region(&timestamps, 0, &mut times)?;
        env.get_double_array_region(&values, 0, &mut soc)?;
        let indices = history_indices(&times, &soc, start, end);
        let array = env.new_int_array(indices.len() as i32)?;
        env.set_int_array_region(&array, 0, &indices)?;
        Ok(array.into_raw())
    })();
    match result { Ok(array) => array, Err(_) => {
        let _ = env.throw_new("java/lang/IllegalStateException", "Native battery history processing failed");
        std::ptr::null_mut()
    }}
}

#[no_mangle]
pub extern "system" fn Java_io_ather_pro_data_computation_RustTelemetryMath_scaleRange(
    _env: JNIEnv, _class: JClass, current: jdouble, mode: jdouble, active: jdouble,
) -> jdouble {
    let value = current * mode / active;
    if value.is_finite() && value >= 0.0 { value } else { f64::NAN }
}

#[no_mangle]
pub extern "system" fn Java_io_ather_pro_data_computation_RustTelemetryMath_chargeEstimate(
    mut env: JNIEnv, _class: JClass, soc: jdouble, target: jdouble, capacity: jdouble,
    tariff: jdouble, range: jdouble, eta80: jdouble, eta100: jdouble,
) -> jdoubleArray {
    let target = target.clamp(0.0, 100.0);
    let remaining = (target - soc).max(0.0);
    let energy = capacity * remaining / 100_000.0;
    let eta = if remaining == 0.0 { 0.0 }
        else if target <= 80.0 && soc < 80.0 && eta80.is_finite() && eta80 >= 0.0 { eta80 * remaining / (80.0 - soc) }
        else if soc < 100.0 && eta100.is_finite() && eta100 >= 0.0 { eta100 * remaining / (100.0 - soc) }
        else { f64::NAN };
    let projected = if soc >= 5.0 && range.is_finite() && range >= 0.0 { range * target / soc } else { f64::NAN };
    let result = (|| -> jni::errors::Result<jdoubleArray> {
        let array = env.new_double_array(5)?;
        env.set_double_array_region(&array, 0, &[remaining, energy, energy * tariff, projected, eta])?;
        Ok(array.into_raw())
    })();
    match result { Ok(array) => array, Err(_) => {
        let _ = env.throw_new("java/lang/IllegalStateException", "Native range processing failed");
        std::ptr::null_mut()
    }}
}

fn usable_rate(rate: f64) -> bool {
    rate.is_finite() && (0.01..=10.0).contains(&rate)
}

/// Learned charge speed. Returns `[percent_per_minute, basis, accuracy]`.
/// `NaN` rate means that side is absent. Accuracy is 10–95 and never 100.
fn learned_charge_numbers(live_rate: f64, live_minutes: f64, learned_rate: f64, learned_minutes: f64) -> [f64; 3] {
    let live_ok = usable_rate(live_rate) && live_minutes.is_finite() && live_minutes > 0.0;
    let past_ok = usable_rate(learned_rate) && learned_minutes.is_finite() && learned_minutes > 0.0;
    let (rate, basis) = if live_ok && past_ok {
        let weight = (live_minutes / 20.0).clamp(0.0, 1.0);
        let blended = live_rate * weight + learned_rate * (1.0 - weight);
        (blended, if weight >= 0.999 { 2.0 } else { 5.0 })
    } else if live_ok {
        (live_rate, 2.0)
    } else if past_ok {
        (learned_rate, 4.0)
    } else {
        return [f64::NAN, f64::NAN, f64::NAN];
    };
    let live_part = (if live_ok { live_minutes } else { 0.0 }).clamp(0.0, 20.0) / 20.0 * 35.0;
    let past_part = (if past_ok { learned_minutes } else { 0.0 }).clamp(0.0, 180.0) / 180.0 * 55.0;
    let accuracy = ((10.0 + live_part + past_part) as i32).clamp(10, 95) as f64;
    [rate, basis, accuracy]
}

#[no_mangle]
pub extern "system" fn Java_io_ather_pro_data_computation_RustTelemetryMath_learnedChargeNumbers(
    mut env: JNIEnv, _class: JClass, live_rate: jdouble, live_minutes: jdouble,
    learned_rate: jdouble, learned_minutes: jdouble,
) -> jdoubleArray {
    let numbers = learned_charge_numbers(live_rate, live_minutes, learned_rate, learned_minutes);
    let result = (|| -> jni::errors::Result<jdoubleArray> {
        let array = env.new_double_array(3)?;
        env.set_double_array_region(&array, 0, &numbers)?;
        Ok(array.into_raw())
    })();
    match result { Ok(array) => array, Err(_) => {
        let _ = env.throw_new("java/lang/IllegalStateException", "Native learned charge rate failed");
        std::ptr::null_mut()
    }}
}

#[cfg(test)]
mod tests {
    use super::{history_indices, learned_charge_numbers};

    #[test]
    fn first_charge_uses_only_the_live_rate() {
        let numbers = learned_charge_numbers(1.0, 2.0, f64::NAN, 0.0);
        assert!((numbers[0] - 1.0).abs() < 1e-9);
        assert_eq!(numbers[1], 2.0);
        assert_eq!(numbers[2], 13.0);
    }

    #[test]
    fn ten_minutes_blends_toward_the_live_rate() {
        let numbers = learned_charge_numbers(1.0, 10.0, 0.5, 60.0);
        assert!((numbers[0] - 0.75).abs() < 1e-9);
        assert_eq!(numbers[1], 5.0);
        assert_eq!(numbers[2], 45.0);
    }

    #[test]
    fn saved_rate_is_used_before_this_charge_has_a_speed() {
        let numbers = learned_charge_numbers(f64::NAN, 0.0, 0.5, 60.0);
        assert!((numbers[0] - 0.5).abs() < 1e-9);
        assert_eq!(numbers[1], 4.0);
    }

    #[test]
    fn history_keeps_finite_samples_in_order_without_duplicate_times() {
        let times = [30_i64, 10, 20, 20, 5];
        let soc = [40.0, 80.0, 70.0, 71.0, f64::NAN];
        let indices = history_indices(&times, &soc, 10, 30);
        assert_eq!(indices, vec![1, 2, 0]);
    }
}
