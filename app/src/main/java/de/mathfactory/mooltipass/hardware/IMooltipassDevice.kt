package de.mathfactory.mooltipass.hardware

interface IMooltipassDevice {

    fun sendPing(): Boolean
    fun getVersion(): String?
    fun setContext(context: String): Int
    fun getLogin(): String?
    fun getPassword(): String?
    fun setLogin(login: String): Boolean
    fun setPassword(password: String): Boolean
    fun addContext(context: String): Boolean
}