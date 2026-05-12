package lava.credentials

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import lava.database.dao.ProviderCredentialBindingDao
import lava.database.entity.ProviderCredentialBindingEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderCredentialBindingImplTest {
    private class FakeDao : ProviderCredentialBindingDao {
        val rows = mutableMapOf<String, ProviderCredentialBindingEntity>()
        private val flows = mutableMapOf<String, MutableStateFlow<ProviderCredentialBindingEntity?>>()
        override fun observe(providerId: String) = flows.getOrPut(providerId) { MutableStateFlow(rows[providerId]) }
        override suspend fun upsert(entity: ProviderCredentialBindingEntity) {
            rows[entity.providerId] = entity
            flows[entity.providerId]?.value = entity
            flows.getOrPut(entity.providerId) { MutableStateFlow(entity) }
        }
        override suspend fun unbind(providerId: String) {
            rows.remove(providerId)
            flows[providerId]?.value = null
        }
    }

    @Test
    fun `bind then observe returns the credential id`() = runBlocking {
        val dao = FakeDao()
        val binding = ProviderCredentialBindingImpl(dao)
        binding.bind("rutracker", "cred-1")
        assertEquals("cred-1", binding.observe("rutracker").first())
    }

    @Test
    fun `unbind clears the binding`() = runBlocking {
        val dao = FakeDao()
        val binding = ProviderCredentialBindingImpl(dao)
        binding.bind("rutor", "cred-1")
        binding.unbind("rutor")
        assertNull(binding.observe("rutor").first())
    }
}
