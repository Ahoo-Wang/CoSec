#
# Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#      http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# The system test's own services (test/system-*), from their installDist: never published, built by the system-test
# workflow for the run. Build context: the project directory; APP: its name.
FROM eclipse-temurin:21-jre-noble

ARG APP
ENV APP=${APP}

RUN groupadd -r app && useradd -r -g app app
COPY --chown=app:app build/install/${APP} /opt/app
WORKDIR /opt/app
USER app
EXPOSE 8080
ENTRYPOINT [ "sh", "-c", "exec bin/$APP" ]
