package digital.vasic.lava.api.di

import digital.vasic.lava.api.api.HttpClientFactory
import lava.network.api.RuTrackerApiFactory
import org.koin.core.context.GlobalContext
import org.koin.core.parameter.ParametersDefinition
import org.koin.core.qualifier.Qualifier
import org.koin.dsl.module

internal val appModule = module {
    single { HttpClientFactory.create() }
    single { RuTrackerApiFactory.create(get()) }
}

internal inline fun <reified T : Any> inject(
    qualifier: Qualifier? = null,
    noinline parameters: ParametersDefinition? = null,
) = lazy { get<T>(qualifier, parameters) }

internal inline fun <reified T : Any> get(
    qualifier: Qualifier? = null,
    noinline parameters: ParametersDefinition? = null,
) = GlobalContext.get().get<T>(qualifier, parameters)
