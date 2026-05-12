package lava.credentials

import kotlinx.coroutines.flow.Flow
import lava.credentials.model.CredentialsEntry

interface CredentialsEntryRepository {
    fun observe(): Flow<List<CredentialsEntry>>
    suspend fun list(): List<CredentialsEntry>
    suspend fun get(id: String): CredentialsEntry?
    suspend fun upsert(entry: CredentialsEntry)
    suspend fun delete(id: String)
}
