package dev.omnibridge.server

import io.javalin.http.HttpResponseException

class CapabilityUnavailable(message: String) : HttpResponseException(501, message)
class TooBusy(message: String) : HttpResponseException(429, message)
