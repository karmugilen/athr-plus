# JSON keys in saved preferences must remain readable across debug/release updates.
# Only reflected persistence models are retained; UI and other app code remain optimizable.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class io.ather.pro.data.local.SavedScooterReading { *; }
-keep class io.ather.pro.domain.model.ModeRange { *; }
-keep class io.ather.pro.domain.chargingmap.ChargerLocation { *; }
-keep class io.ather.pro.domain.chargingmap.ChargerConnector { *; }
-keep class io.ather.pro.domain.chargingmap.TariffLine { *; }
-keep,allowoptimization class io.ather.pro.domain.model.TripRecord { *; }
-keep,allowoptimization class io.ather.pro.domain.model.TelemetrySample { *; }
-keep,allowoptimization class io.ather.pro.domain.model.GpsData { *; }
-keep,allowoptimization class io.ather.pro.domain.range.RideModeRange { *; }
-keep,allowoptimization class io.ather.pro.domain.charging.ChargeTimeEstimate { *; }
-keep,allowoptimization class io.ather.pro.domain.update.AppRelease { *; }
-keep,allowoptimization class io.ather.pro.domain.insights.RiderInsights { *; }
-keep,allowoptimization class io.ather.pro.domain.insights.ChargeSession { *; }
-keep,allowoptimization class io.ather.pro.domain.insights.ParkedPeriod { *; }
-keep,allowoptimization class io.ather.pro.domain.insights.BatteryObservation { *; }

# Exported Rust symbols use this exact Kotlin/JVM class and method names.
-keep,allowoptimization class io.ather.pro.data.computation.RustTelemetryMath { *; }

# JavaScript invokes these bridge methods by their annotated names.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
