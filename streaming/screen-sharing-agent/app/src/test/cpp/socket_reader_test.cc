/*
 * Copyright (C) 2026 The Android Open Source Project
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

#include <fcntl.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <cstring>
#include <thread>
#include <utility>

#include <gtest/gtest.h>

namespace screensharing {
namespace {

using namespace std;
using namespace std::chrono;

class SocketReaderTest : public ::testing::Test {
protected:
  void SetUp() override {
    ASSERT_EQ(socketpair(AF_UNIX, SOCK_STREAM, 0, fds_), 0);
    int flags = fcntl(fds_[0], F_GETFL);
    ASSERT_GE(flags, 0);
    ASSERT_EQ(fcntl(fds_[0], F_SETFL, flags | O_NONBLOCK), 0);
  }

  void TearDown() override {
    for (int& fd : fds_) {
      if (fd >= 0) {
        close(fd);
        fd = -1;
      }
    }
  }

  void CloseReader() {
    if (fds_[0] >= 0) {
      close(fds_[0]);
      fds_[0] = -1;
    }
  }

  void CloseWriter() {
    if (fds_[1] >= 0) {
      close(fds_[1]);
      fds_[1] = -1;
    }
  }

  int reader_fd() const { return fds_[0]; }
  int writer_fd() const { return fds_[1]; }

private:
  int fds_[2] = {-1, -1};
};

TEST_F(SocketReaderTest, ReadsAvailableData) {
  const char payload[] = "hello";
  ASSERT_EQ(write(writer_fd(), payload, strlen(payload)), static_cast<ssize_t>(strlen(payload)));

  errno = EAGAIN;
  SocketReader reader(reader_fd(), 1000);
  char buf[16] = {};
  auto result = reader.Read(buf, sizeof(buf));
  EXPECT_EQ(result.status, SocketReader::Status::SUCCESS);
  EXPECT_EQ(result.error_code, 0);
  EXPECT_EQ(result.bytes_read, strlen(payload));
  EXPECT_STREQ(buf, payload);
}

TEST_F(SocketReaderTest, ReturnsDisconnectedOnEofWhenErrnoIsEagain) {
  // Regression test for b/590086001: read() returning 0 does not clear errno,
  // which previously caused SocketReader::Read to busy-spin until timeout when errno == EAGAIN.
  CloseWriter();
  errno = EAGAIN;

  SocketReader reader(reader_fd(), 5000);
  char buf[16];
  auto result = reader.Read(buf, sizeof(buf));

  EXPECT_EQ(result.status, SocketReader::Status::DISCONNECTED);
  EXPECT_EQ(result.error_code, 0);
  EXPECT_EQ(result.bytes_read, 0u);
}

TEST_F(SocketReaderTest, ReturnsDisconnectedOnEofWhenErrnoIsOtherError) {
  CloseWriter();
  errno = EINVAL;

  SocketReader reader(reader_fd(), 1000);
  char buf[16];
  auto result = reader.Read(buf, sizeof(buf));

  EXPECT_EQ(result.status, SocketReader::Status::DISCONNECTED);
  EXPECT_EQ(result.error_code, 0);
  EXPECT_EQ(result.bytes_read, 0u);
}

TEST_F(SocketReaderTest, ReturnsDisconnectedOnConnectionReset) {
  // Closing the peer socket while it has unread data in its receive queue causes
  // read() on the local AF_UNIX stream socket to fail with ECONNRESET.
  ASSERT_EQ(write(reader_fd(), "x", 1), 1);
  CloseWriter();

  SocketReader reader(reader_fd(), 1000);
  char buf[16];
  auto result = reader.Read(buf, sizeof(buf));

  EXPECT_EQ(result.status, SocketReader::Status::DISCONNECTED);
  EXPECT_EQ(result.error_code, ECONNRESET);
  EXPECT_EQ(result.bytes_read, 0u);
}

TEST_F(SocketReaderTest, ReturnsDisconnectedWhenPeerClosesDuringPoll) {
  SocketReader reader(reader_fd(), 5000);
  thread closer([this]() {
    this_thread::sleep_for(50ms);
    CloseWriter();
  });

  char buf[16];
  auto result = reader.Read(buf, sizeof(buf));
  closer.join();

  EXPECT_EQ(result.status, SocketReader::Status::DISCONNECTED);
  EXPECT_EQ(result.error_code, 0);
  EXPECT_EQ(result.bytes_read, 0u);
}

TEST_F(SocketReaderTest, ReadsDataWrittenDuringPoll) {
  SocketReader reader(reader_fd(), 5000);
  thread writer([this]() {
    this_thread::sleep_for(50ms);
    ssize_t written = write(writer_fd(), "abc", 3);
    EXPECT_EQ(written, 3);
    if (written != 3) {
      CloseWriter();
    }
  });

  char buf[16] = {};
  auto result = reader.Read(buf, sizeof(buf));
  writer.join();

  EXPECT_EQ(result.status, SocketReader::Status::SUCCESS);
  EXPECT_EQ(result.error_code, 0);
  EXPECT_EQ(result.bytes_read, 3u);
  EXPECT_STREQ(buf, "abc");
}

TEST_F(SocketReaderTest, TimesOutWhenNoDataArrives) {
  SocketReader reader(reader_fd(), 50);
  char buf[16];
  auto start = steady_clock::now();
  auto result = reader.Read(buf, sizeof(buf));
  auto elapsed = steady_clock::now() - start;

  EXPECT_EQ(result.status, SocketReader::Status::TIMEOUT);
  EXPECT_EQ(result.error_code, 0);
  EXPECT_EQ(result.bytes_read, 0u);
  EXPECT_GE(elapsed, 40ms);
}

TEST_F(SocketReaderTest, InfiniteTimeoutWaitsForData) {
  SocketReader reader(reader_fd());
  thread writer([this]() {
    this_thread::sleep_for(30ms);
    ssize_t written = write(writer_fd(), "ok", 2);
    EXPECT_EQ(written, 2);
    if (written != 2) {
      CloseWriter();
    }
  });

  char buf[16] = {};
  auto result = reader.Read(buf, sizeof(buf));
  writer.join();

  EXPECT_EQ(result.status, SocketReader::Status::SUCCESS);
  EXPECT_EQ(result.error_code, 0);
  EXPECT_EQ(result.bytes_read, 2u);
  EXPECT_STREQ(buf, "ok");
}

TEST_F(SocketReaderTest, ReturnsDisconnectedOnBadFileDescriptor) {
  int fd = reader_fd();
  SocketReader reader(fd, 1000);
  CloseReader();

  char buf[16];
  auto result = reader.Read(buf, sizeof(buf));

  EXPECT_EQ(result.status, SocketReader::Status::DISCONNECTED);
  EXPECT_EQ(result.error_code, EBADF);
  EXPECT_EQ(result.bytes_read, 0u);
}

TEST_F(SocketReaderTest, ReturnsIoErrorWithErrnoWhenReadFails) {
  int dir_fd = open(".", O_RDONLY | O_DIRECTORY);
  ASSERT_GT(dir_fd, 0);
  SocketReader reader(dir_fd, 1000);

  char buf[16];
  auto result = reader.Read(buf, sizeof(buf));
  close(dir_fd);

  EXPECT_EQ(result.status, SocketReader::Status::IO_ERROR);
  EXPECT_EQ(result.error_code, EISDIR);
  EXPECT_EQ(result.bytes_read, 0u);
}

TEST_F(SocketReaderTest, ReturnsIoErrorWhenSizeIsZero) {
  SocketReader reader(reader_fd(), 1000);
  char buf[1];
  auto result = reader.Read(buf, 0);

  EXPECT_EQ(result.status, SocketReader::Status::IO_ERROR);
  EXPECT_EQ(result.error_code, EINVAL);
  EXPECT_EQ(result.bytes_read, 0u);
}

TEST_F(SocketReaderTest, ZeroTimeoutReturnsImmediatelyWhenNoDataAvailable) {
  SocketReader reader(reader_fd(), 0);
  char buf[16];
  auto result = reader.Read(buf, sizeof(buf));

  EXPECT_EQ(result.status, SocketReader::Status::TIMEOUT);
  EXPECT_EQ(result.error_code, 0);
  EXPECT_EQ(result.bytes_read, 0u);
}

TEST_F(SocketReaderTest, SetTimeoutMillis) {
  SocketReader reader(reader_fd(), 1000);
  EXPECT_EQ(reader.socket_fd(), reader_fd());
  EXPECT_EQ(reader.timeout_millis(), 1000);

  reader.set_timeout_millis(250);
  EXPECT_EQ(reader.timeout_millis(), 250);
}

TEST_F(SocketReaderTest, MoveConstructor) {
  int expected_fd = reader_fd();
  SocketReader reader(expected_fd, 250);

  SocketReader moved(std::move(reader));
  EXPECT_EQ(moved.socket_fd(), expected_fd);
  EXPECT_EQ(moved.timeout_millis(), 250);
  EXPECT_EQ(reader.socket_fd(), -1);  // NOLINT(bugprone-use-after-move)
}

}  // namespace
}  // namespace screensharing
