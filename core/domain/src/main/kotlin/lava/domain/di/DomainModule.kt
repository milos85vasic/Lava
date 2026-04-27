package lava.domain.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import lava.domain.usecase.AddEndpointUseCase
import lava.domain.usecase.AddEndpointUseCaseImpl
import lava.domain.usecase.AppLaunchedUseCase
import lava.domain.usecase.AppLaunchedUseCaseImpl
import lava.domain.usecase.DisableRatingRequestUseCase
import lava.domain.usecase.DisableRatingRequestUseCaseImpl
import lava.domain.usecase.DiscoverLocalEndpointsUseCase
import lava.domain.usecase.DiscoverLocalEndpointsUseCaseImpl
import lava.domain.usecase.GetRatingStoreUseCase
import lava.domain.usecase.GetRatingStoreUseCaseImpl
import lava.domain.usecase.LogoutUseCase
import lava.domain.usecase.LogoutUseCaseImpl
import lava.domain.usecase.ObserveAuthStateUseCase
import lava.domain.usecase.ObserveAuthStateUseCaseImpl
import lava.domain.usecase.ObserveEndpointStatusUseCase
import lava.domain.usecase.ObserveEndpointStatusUseCaseImpl
import lava.domain.usecase.ObserveEndpointsStatusUseCase
import lava.domain.usecase.ObserveEndpointsStatusUseCaseImpl
import lava.domain.usecase.ObserveRatingRequestUseCase
import lava.domain.usecase.ObserveRatingRequestUseCaseImpl
import lava.domain.usecase.PostponeRatingRequestUseCase
import lava.domain.usecase.PostponeRatingRequestUseCaseImpl
import lava.domain.usecase.RemoveEndpointUseCase
import lava.domain.usecase.RemoveEndpointUseCaseImpl
import lava.domain.usecase.SetEndpointUseCase
import lava.domain.usecase.SetEndpointUseCaseImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface DomainModule {
    @Binds
    @Singleton
    fun addEndpointUseCase(impl: AddEndpointUseCaseImpl): AddEndpointUseCase

    @Binds
    @Singleton
    fun discoverLocalEndpointsUseCase(impl: DiscoverLocalEndpointsUseCaseImpl): DiscoverLocalEndpointsUseCase

    @Binds
    @Singleton
    fun disableRatingRequestUseCase(impl: DisableRatingRequestUseCaseImpl): DisableRatingRequestUseCase

    @Binds
    @Singleton
    fun getRatingStoreUseCase(impl: GetRatingStoreUseCaseImpl): GetRatingStoreUseCase

    @Binds
    @Singleton
    fun incrementLaunchCountUseCase(impl: AppLaunchedUseCaseImpl): AppLaunchedUseCase

    @Binds
    @Singleton
    fun logoutUseCase(impl: LogoutUseCaseImpl): LogoutUseCase

    @Binds
    @Singleton
    fun observeAuthStateUseCase(impl: ObserveAuthStateUseCaseImpl): ObserveAuthStateUseCase

    @Binds
    @Singleton
    fun observeEndpointStatusUseCase(impl: ObserveEndpointStatusUseCaseImpl): ObserveEndpointStatusUseCase

    @Binds
    @Singleton
    fun observeEndpointsStatusUseCase(impl: ObserveEndpointsStatusUseCaseImpl): ObserveEndpointsStatusUseCase

    @Binds
    @Singleton
    fun observeRatingRequestUseCase(impl: ObserveRatingRequestUseCaseImpl): ObserveRatingRequestUseCase

    @Binds
    @Singleton
    fun postponeRatingRequestUseCase(impl: PostponeRatingRequestUseCaseImpl): PostponeRatingRequestUseCase

    @Binds
    @Singleton
    fun removeEndpointUseCase(impl: RemoveEndpointUseCaseImpl): RemoveEndpointUseCase

    @Binds
    @Singleton
    fun setEndpointUseCase(impl: SetEndpointUseCaseImpl): SetEndpointUseCase
}
