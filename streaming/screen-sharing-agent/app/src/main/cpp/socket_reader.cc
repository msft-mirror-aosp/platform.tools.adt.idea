/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "socket_reader.h"

#include <poll.h>
#include <unistd.h>

#include <cassert>
#include <cerrno>
#include <chrono>
#include <optional>

namespace screensharing {

using namespace std;
using namespace std::chrono;

SocketReader::SocketReader(int socket_fd, int32_t timeout_millis)
    : socket_fd_(socket_fd),
      timeout_millis_(timeout_millis) {
  assert(socket_fd > 0);
}

SocketReader::SocketReader(SocketReader&& other)
    : socket_fd_(other.socket_fd_),
      timeout_millis_(other.timeout_millis_) {
  other.socket_fd_ = -1;
}

SocketReader::Result SocketReader::Read(void* buf, size_t size) {
  if (size == 0) {
    return Result(Status::IO_ERROR, EINVAL);
  }
  optional<steady_clock::time_point> deadline;
  if (timeout_millis_ >= 0) {
    deadline = steady_clock::now() + milliseconds(timeout_millis_);
  }
  while (true) {
    ssize_t bytes_read = TEMP_FAILURE_RETRY(read(socket_fd_, buf, size));
    if (bytes_read == 0) {
      return Result(Status::DISCONNECTED);
    }
    if (bytes_read < 0) {
      switch (errno) {
        case EBADF:
        case ECONNRESET:
        case EPIPE:
        case ENOENT:
          return Result(Status::DISCONNECTED, errno);

        case EAGAIN: {
          int poll_timeout_millis = -1;
          if (deadline.has_value()) {
            auto remaining_time_millis = ceil<milliseconds>(*deadline - steady_clock::now()).count();
            if (remaining_time_millis <= 0) {
              return Result(Status::TIMEOUT);
            }
            poll_timeout_millis = static_cast<int>(remaining_time_millis);
          }
          struct pollfd fds = {socket_fd_, POLLIN, 0};
          int ret = poll(&fds, 1, poll_timeout_millis);
          if (ret == 0) {
            return Result(Status::TIMEOUT);
          }
          if (ret < 0 && errno != EINTR) {
            return Result(Status::IO_ERROR, errno);
          }
          continue;
        }

        default:
          return Result(Status::IO_ERROR, errno);
      }
    }
    return Result(Status::SUCCESS, 0, static_cast<size_t>(bytes_read));
  }
}

}  // namespace screensharing
