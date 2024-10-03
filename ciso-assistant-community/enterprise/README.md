## Testing locally 🚀

To run CISO Assistant Enterprise locally in a straightforward way, you can use Docker compose.

1. Make sure you are located in the enterprise directory of the repository

2. Launch docker-compose script for prebuilt images:

```sh
./docker-compose.sh
```

_Alternatively_, you can use this variant to build the docker images for your specific architecture:

```sh
./docker-compose-build.sh
```

When asked for, enter your email and password for your superuser.

You can then reach CISO Assistant using your web browser at [https://localhost:8443/](https://localhost:8443/)
