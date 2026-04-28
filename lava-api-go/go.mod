module digital.vasic.lava.apigo

go 1.24

replace (
	digital.vasic.auth => ../Submodules/Auth
	digital.vasic.cache => ../Submodules/Cache
	digital.vasic.challenges => ../Submodules/Challenges
	digital.vasic.config => ../Submodules/Config
	digital.vasic.containers => ../Submodules/Containers
	digital.vasic.database => ../Submodules/Database
	digital.vasic.discovery => ../Submodules/Discovery
	digital.vasic.http3 => ../Submodules/HTTP3
	digital.vasic.mdns => ../Submodules/Mdns
	digital.vasic.middleware => ../Submodules/Middleware
	digital.vasic.observability => ../Submodules/Observability
	digital.vasic.ratelimiter => ../Submodules/RateLimiter
	digital.vasic.recovery => ../Submodules/Recovery
	digital.vasic.security => ../Submodules/Security
)
