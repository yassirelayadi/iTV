package fr.poptv.player.ui

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/** Code HTTP renvoyé par le serveur pour ce flux, s'il y en a un (401, 403, 404, 458…). */
fun PlaybackException.httpCode(): Int? {
    var c: Throwable? = this
    while (c != null) {
        if (c is HttpDataSource.InvalidResponseCodeException) return c.responseCode
        c = c.cause
    }
    return null
}

/** Codes qui signifient le plus souvent « une connexion est déjà ouverte » (limite d'une seule connexion). */
private val LIMIT_CODES = setOf(403, 429, 456, 458, 503, 509)

/** Erreur qu'un nouvel essai quelques secondes plus tard a des chances de régler (flux précédent pas encore fermé). */
fun PlaybackException.isConnectionLimit(): Boolean = httpCode() in LIMIT_CODES

/** Message en français, compréhensible, pour une erreur de lecture. */
fun PlaybackException.friendlyMessage(): String {
    val code = httpCode()
    return when {
        code == 401 -> "Le serveur refuse l'accès (code 401) : identifiants ou abonnement à vérifier."
        code in LIMIT_CODES -> "Le serveur refuse une connexion de plus (code $code). Une autre appli ou un autre appareil utilise peut-être ton abonnement : ferme-le, puis réessaie."
        code == 404 || code == 410 -> "Ce titre n'est plus disponible sur le serveur (code $code)."
        code != null -> "Le serveur a répondu par une erreur (code $code)."
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Connexion au serveur impossible. Vérifie la connexion internet."
        errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "Format vidéo non pris en charge par cet appareil."
        else -> "Lecture impossible ($errorCodeName)."
    }
}
