# kotlinx.serialization embarque ses propres règles R8 ; on garde en plus nos modèles de protocole.
-keep,includedescriptorclasses class dev.aura.mobile.protocol.**$$serializer { *; }
-keepclassmembers class dev.aura.mobile.protocol.** {
  *** Companion;
}
-keepclasseswithmembers class dev.aura.mobile.protocol.** {
  kotlinx.serialization.KSerializer serializer(...);
}
