# Réduction du code uniquement : ni renommage ni optimisation agressive.
# Raison : les traces de plantage affichées par l'app restent lisibles, et le risque de casse à l'exécution est minimal.
-dontobfuscate
-dontoptimize

# kotlinx.serialization : classes @Serializable (identifiants, caches, favoris)
-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class fr.poptv.player.**$$serializer { *; }
-keepclassmembers class fr.poptv.player.** { *** Companion; }
-keepclasseswithmembers class fr.poptv.player.** { kotlinx.serialization.KSerializer serializer(...); }

# Bibliothèques réseau : classes optionnelles absentes sur Android
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
