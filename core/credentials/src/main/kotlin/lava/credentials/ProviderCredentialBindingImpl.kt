package lava.credentials

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import lava.database.dao.ProviderCredentialBindingDao
import lava.database.entity.ProviderCredentialBindingEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderCredentialBindingImpl @Inject constructor(
    private val dao: ProviderCredentialBindingDao,
) : ProviderCredentialBinding {
    override suspend fun bind(providerId: String, credentialId: String) {
        dao.upsert(ProviderCredentialBindingEntity(providerId, credentialId))
    }
    override suspend fun unbind(providerId: String) = dao.unbind(providerId)
    override fun observe(providerId: String): Flow<String?> =
        dao.observe(providerId).map { it?.credentialId }
}
