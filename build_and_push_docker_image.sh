docker build -t lava-app-proxy ./proxy
docker tag lava-app-proxy registry.digitalocean.com/lava-app/lava-app-proxy
docker push registry.digitalocean.com/lava-app/lava-app-proxy
