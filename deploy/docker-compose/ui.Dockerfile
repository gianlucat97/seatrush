# The image of the demo client (README sections 6.7 and 23).
#
# A static bundle behind nginx, which is also what makes the claim of section 6.7 true rather
# than asserted: the page and the API are served from one origin, so nothing in this
# repository configures CORS. The dev server of `make ui` proxies `/api` for exactly the same
# reason; this is that arrangement in the stack rather than on a developer's machine.
#
# Built from the repository root:
#   docker build --file deploy/docker-compose/ui.Dockerfile .

# ---------------------------------------------------------------------------------------
FROM node:24-alpine AS build

WORKDIR /src

# The lock file first, so a change to a component does not reinstall the dependency tree.
COPY frontend/seat-map-ui/package.json frontend/seat-map-ui/package-lock.json ./
# From the lock file, which is what makes this image the build CI gates rather than whatever
# the registry resolves today.
RUN npm ci

COPY frontend/seat-map-ui/ ./
# Type checks and then bundles, which is the same script `make ui-build` runs.
RUN npm run build

# ---------------------------------------------------------------------------------------
FROM nginx:1.29-alpine AS runtime

COPY deploy/docker-compose/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist/ /usr/share/nginx/html/

EXPOSE 80
