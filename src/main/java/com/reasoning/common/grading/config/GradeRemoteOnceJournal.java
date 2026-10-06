package com.reasoning.common.grading.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.reasoning.common.grading.model.SnapshotJson;
import com.sun.security.auth.module.UnixSystem;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 사전 provision한 단일 호스트 SERVER+WORKER 저널이다. 자격 교체에도 같은 경로·헤더·lock inode를 유지한다. 이전 자격 폐기는 별도 배포
 * 필수조건이며 여기서 증명하지 않는다. 협력하는 로컬 프로세스만 배제하며 분산 인가나 파일시스템 증명을 제공하지 않는다. 신뢰된 호스트의 경로 불변성에 의존하고 검사/열기
 * TOCTOU는 남는다. 기록 frame은 big-endian 길이4 + payload90 + SHA256 32이다. payload는 UUID16·세대8·상태1·시도1·
 * 자격digest32·원래시도hash32 순서다. 상태 0=IN_FLIGHT, 1=OWNED, 2=ABANDONED, 3=CHAT_INTENT,
 * 4=COMPLETION_INTENT, 5=CLOSED다. 원문·의미 결과·nano 앵커는 저장하지 않는다.
 */
public final class GradeRemoteOnceJournal implements AutoCloseable {
    private static final Set<Path> OPEN = new HashSet<>();
    private static final Set<PosixFilePermission> FILE_MODE =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> DIRECTORY_MODE =
            Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
    private static final int RECORD_BYTES = 90;
    private final Scope scope;
    private final Path lockPath;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final FileChannel journal;
    private final long capacity;
    private final Force force;
    private final Map<Key, Saved> history = new HashMap<>();
    private long end;
    private boolean poisoned;
    private boolean closed;

    /** 배포 조립의 비밀 아닌 일치 좌표이며 bearer나 인증 증명이 아니다. */
    public record Scope(UUID serverScopeId, String workerKey, String credentialSha256) {
        /**
         * 좌표 문법만 확인한다. 신뢰된 조립이 transport와 실제 등록 digest를 결속해야 한다.
         *
         * @param serverScopeId null·영 UUID 불가
         * @param workerKey ASCII 영숫자·밑줄·하이픈 1~80자, null 불가
         * @param credentialSha256 소문자64hex 실제 자격 digest, null 불가
         * @throws IllegalArgumentException 원인 없는 INVALID_REMOTE_JOURNAL
         */
        public Scope {
            if (serverScopeId == null
                    || serverScopeId.equals(new UUID(0, 0))
                    || workerKey == null
                    || !workerKey.matches("[A-Za-z0-9_-]{1,80}")
                    || credentialSha256 == null
                    || !credentialSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("INVALID_REMOTE_JOURNAL");
        }

        @Override
        public String toString() {
            return "Scope[redacted]";
        }
    }

    private record Key(UUID jobKey, long leaseGen) {}

    private record Saved(byte state, int attemptNo, String originalHash, String credentialHash) {}

    /** 실제 channel의 force만 시험에서 실패시키는 패키지 내부 경계다. 운영은 항상 force(true)다. */
    @FunctionalInterface
    interface Force {
        void apply(FileChannel channel) throws IOException;
    }

    /**
     * 닫힌 보호 descriptor를 읽고 기존 파일만 잠근다. 디렉터리·파일 생성과 대체·복구는 수행하지 않는다.
     *
     * @param descriptorPath canonical 절대 경로의 외부 설치 보호 문서, null·공백 불가
     * @return 헤더 검증·force와 전체 기록 검증을 완료한 배타 소유자
     * @throws IOException 경로·mode·UID·APFS/ext4/XFS·용량·lock·기록·force 실패의 고정 오류
     */
    public static GradeRemoteOnceJournal open(String descriptorPath) throws IOException {
        return open(descriptorPath, channel -> channel.force(true));
    }

    /**
     * 실제 파일을 유지하면서 force 경계 실패만 주입하는 내부 시험 경로다.
     *
     * @param descriptorPath 운영과 동일한 보호 경로, null·공백 불가
     * @param force null 불가인 실제 channel force 경계
     * @return 운영과 동일한 파일 잠금·파싱 소유자
     * @throws IOException 원인 없는 INVALID_REMOTE_JOURNAL
     */
    static GradeRemoteOnceJournal open(String descriptorPath, Force force) throws IOException {
        FileChannel lockChannel = null;
        FileChannel channel = null;
        FileLock lock = null;
        Path lockPath = null;
        boolean registered = false;
        try {
            if (descriptorPath == null || descriptorPath.isBlank() || force == null)
                throw failure();
            byte[] bytes = GradeProtectedDocumentReader.read(descriptorPath);
            Path descriptor = Path.of(descriptorPath);
            check(descriptor, false);
            Path directory = descriptor.getParent();
            check(directory, true);
            String type = Files.getFileStore(directory).type().toLowerCase(java.util.Locale.ROOT);
            if (!Set.of("apfs", "ext4", "xfs").contains(type)) throw failure();
            JsonNode node = SnapshotJson.parse(bytes);
            keys(
                    node,
                    Set.of(
                            "formatNo",
                            "serverScopeId",
                            "workerKey",
                            "credentialSha256",
                            "journalFile",
                            "lockFile",
                            "capacityBytes"));
            if (integer(node.get("formatNo")) != 1) throw failure();
            String uuidText = text(node, "serverScopeId");
            UUID uuid = UUID.fromString(uuidText);
            if (!uuid.toString().equals(uuidText)) throw failure();
            Scope scope = new Scope(uuid, text(node, "workerKey"), text(node, "credentialSha256"));
            String journalName = leaf(text(node, "journalFile"));
            String lockName = leaf(text(node, "lockFile"));
            if (journalName.equals(lockName)
                    || journalName.equals(descriptor.getFileName().toString())
                    || lockName.equals(descriptor.getFileName().toString())) throw failure();
            long capacity = integer(node.get("capacityBytes"));
            if (capacity <= 0) throw failure();
            Path journalPath = directory.resolve(journalName);
            lockPath = directory.resolve(lockName);
            check(journalPath, false);
            check(lockPath, false);
            var store = Files.getFileStore(directory);
            if (!store.equals(Files.getFileStore(journalPath))
                    || !store.equals(Files.getFileStore(lockPath))
                    || !store.equals(Files.getFileStore(descriptor))) throw failure();
            Object lockKey =
                    Files.readAttributes(
                                    lockPath, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                            .fileKey();
            Object journalKey =
                    Files.readAttributes(
                                    journalPath,
                                    PosixFileAttributes.class,
                                    LinkOption.NOFOLLOW_LINKS)
                            .fileKey();
            if (lockKey == null || journalKey == null || lockKey.equals(journalKey))
                throw failure();
            synchronized (OPEN) {
                if (!OPEN.add(lockPath)) throw failure();
                registered = true;
            }
            lockChannel =
                    FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            lock = lockChannel.tryLock();
            if (lock == null) throw failure();
            channel =
                    FileChannel.open(
                            journalPath,
                            StandardOpenOption.READ,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS);
            check(lockPath, false);
            check(journalPath, false);
            if (!lockKey.equals(
                            Files.readAttributes(
                                            lockPath,
                                            PosixFileAttributes.class,
                                            LinkOption.NOFOLLOW_LINKS)
                                    .fileKey())
                    || !journalKey.equals(
                            Files.readAttributes(
                                            journalPath,
                                            PosixFileAttributes.class,
                                            LinkOption.NOFOLLOW_LINKS)
                                    .fileKey())) throw failure();
            var owner =
                    new GradeRemoteOnceJournal(
                            scope, lockPath, lockChannel, lock, channel, capacity, force);
            owner.load();
            force.apply(channel);
            return owner;
        } catch (IOException | RuntimeException | LinkageError exception) {
            if (channel != null)
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            if (lock != null)
                try {
                    lock.release();
                } catch (IOException ignored) {
                }
            if (lockChannel != null)
                try {
                    lockChannel.close();
                } catch (IOException ignored) {
                }
            if (registered)
                synchronized (OPEN) {
                    OPEN.remove(lockPath);
                }
            throw failure();
        }
    }

    private GradeRemoteOnceJournal(
            Scope scope,
            Path lockPath,
            FileChannel lockChannel,
            FileLock lock,
            FileChannel journal,
            long capacity,
            Force force) {
        this.scope = scope;
        this.lockPath = lockPath;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.journal = journal;
        this.capacity = capacity;
        this.force = force;
    }

    public Scope scope() {
        return scope;
    }

    /**
     * 실제 journal/lock 수명이 여전히 유효한지 확인한다. disk metadata를 authority로 변환하지 않는다.
     *
     * @throws IOException 닫힘·오염·lock 상실의 원인 없는 INVALID_REMOTE_JOURNAL
     */
    public synchronized void requireHeld() throws IOException {
        requireOpen();
    }

    /**
     * 외부 provisioner가 기존 journal에 미리 저장·force할 정확한 헤더 frame을 만든다. 파일은 쓰지 않는다. 헤더는 credential 회전과
     * 무관하다. 빈 파일은 open에서 거절하며 초기화하지 않는다.
     *
     * @param scope null 불가인 신뢰된 배포 좌표
     * @return big-endian 길이32bit + ASCII 헤더 + SHA256 원본32byte
     */
    public static byte[] provisionedHeader(Scope scope) {
        if (scope == null) throw new IllegalArgumentException("INVALID_REMOTE_JOURNAL");
        return frame(
                ("GRADE_REMOTE_ONCE-v1\n" + scope.serverScopeId() + "\n" + scope.workerKey() + "\n")
                        .getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * START보다 먼저 불가역 tombstone을 append하고 force한다. 재시작의 모든 기존 key는 거절한다.
     *
     * @param jobKey null·영 UUID 불가
     * @param leaseGen 양수 long 실제 서버 세대
     * @throws IOException 중복·닫힘·오염·write·force·capacity 실패의 고정 오류
     */
    public synchronized void inFlight(UUID jobKey, long leaseGen) throws IOException {
        requireOpen();
        Key key = key(jobKey, leaseGen);
        if (history.containsKey(key)) throw failure();
        append(key, new Saved((byte) 0, 0, "0".repeat(64), scope.credentialSha256()));
    }

    /**
     * 현재 invocation의 NEW 검증 뒤에만 사용할 최소 동등성 기록이다. 디스크 기록은 실행 권위가 아니다.
     *
     * @param jobKey 현재 IN_FLIGHT 작업, null 불가
     * @param leaseGen 현재 양수 세대
     * @param attemptNo 실제 NEW의 1~3 시도
     * @param originalHash 소문자64hex 동등성 해시, null 불가
     * @throws IOException 전이·형식·durability 위반의 고정 오류
     */
    public synchronized void owned(UUID jobKey, long leaseGen, int attemptNo, String originalHash)
            throws IOException {
        requireOpen();
        Key key = key(jobKey, leaseGen);
        Saved prior = history.get(key);
        if (prior == null
                || prior.state != 0
                || attemptNo < 1
                || attemptNo > 3
                || originalHash == null
                || !originalHash.matches("[0-9a-f]{64}")) throw failure();
        append(key, new Saved((byte) 1, attemptNo, originalHash, scope.credentialSha256()));
    }

    /**
     * 예약만 수행한 slice1의 소유권을 포기한다. 모델 성공·COMPLETE·환급을 의미하지 않는다.
     *
     * @param jobKey 현재 기록된 작업, null 불가
     * @param leaseGen 현재 양수 세대
     * @throws IOException 전이·닫힘·force·capacity 실패의 고정 오류
     */
    public synchronized void abandon(UUID jobKey, long leaseGen) throws IOException {
        requireOpen();
        Key key = key(jobKey, leaseGen);
        Saved prior = history.get(key);
        if (prior == null || prior.state == 2 || prior.state == 5) throw failure();
        append(key, new Saved((byte) 2, prior.attemptNo, prior.originalHash, prior.credentialHash));
    }

    /**
     * 실제 fresh fence 뒤 chat 전 불가역 의도를 force한다. 반복 훅은 전이 실패다.
     *
     * @param jobKey 현재 OWNED 작업, null 불가
     * @param leaseGen 현재 양수 세대
     * @throws IOException 상태·쓰기·force 실패의 고정 오류; provider 전송 금지
     */
    public synchronized void chatIntent(UUID jobKey, long leaseGen) throws IOException {
        move(jobKey, leaseGen, (byte) 1, (byte) 3);
    }

    /**
     * genuine MODEL 결과/분류 오류 또는 명시 ENGINE_ERROR의 완료 의도를 force한다.
     *
     * @param jobKey 현재 작업, null 불가
     * @param leaseGen 현재 양수 세대
     * @param model true는 CHAT_INTENT에서만, false는 모델 I/O 없는 OWNED에서만 허용
     * @throws IOException 닫힌 전이·force 실패의 고정 오류
     */
    public synchronized void completionIntent(UUID jobKey, long leaseGen, boolean model)
            throws IOException {
        move(jobKey, leaseGen, model ? (byte) 3 : (byte) 1, (byte) 4);
    }

    /**
     * 실제 검증 영수증의 수신을 force한다. accepted=false도 terminal refusal이며 재전송하지 않는다.
     *
     * @param jobKey 현재 COMPLETION_INTENT 작업, null 불가
     * @param leaseGen 현재 양수 세대
     * @throws IOException 상태·force 실패의 고정 오류
     */
    public synchronized void closed(UUID jobKey, long leaseGen) throws IOException {
        move(jobKey, leaseGen, (byte) 4, (byte) 5);
    }

    /** 기존 최소 메타데이터를 보존한 닫힌 전이만 append한다. */
    private void move(UUID jobKey, long leaseGen, byte expected, byte next) throws IOException {
        requireOpen();
        Key key = key(jobKey, leaseGen);
        Saved prior = history.get(key);
        if (prior == null || prior.state != expected) throw failure();
        append(key, new Saved(next, prior.attemptNo, prior.originalHash, prior.credentialHash));
    }

    /**
     * 전체 frame을 검증하여 refusal metadata만 복원한다. 빈 파일·후행 일부 frame도 거절한다.
     *
     * @throws IOException 헤더·checksum·전이·capacity 위반의 고정 오류
     */
    private void load() throws IOException {
        end = journal.size();
        if (end <= 0 || end > capacity) throw failure();
        byte[] expected = provisionedHeader(scope);
        byte[] actual = new byte[expected.length];
        readFully(ByteBuffer.wrap(actual));
        if (!Arrays.equals(expected, actual)) throw failure();
        while (journal.position() < end) {
            ByteBuffer length = ByteBuffer.allocate(4);
            readFully(length);
            length.flip();
            if (length.getInt() != RECORD_BYTES) throw failure();
            byte[] payload = new byte[RECORD_BYTES];
            readFully(ByteBuffer.wrap(payload));
            byte[] checksum = new byte[32];
            readFully(ByteBuffer.wrap(checksum));
            if (!MessageDigest.isEqual(digest(payload), checksum)) throw failure();
            ByteBuffer record = ByteBuffer.wrap(payload);
            Key key = key(new UUID(record.getLong(), record.getLong()), record.getLong());
            byte state = record.get();
            int attempt = Byte.toUnsignedInt(record.get());
            byte[] credential = new byte[32];
            record.get(credential);
            byte[] original = new byte[32];
            record.get(original);
            Saved next =
                    new Saved(
                            state,
                            attempt,
                            HexFormat.of().formatHex(original),
                            HexFormat.of().formatHex(credential));
            validate(history.get(key), next);
            history.put(key, next);
        }
        if (journal.position() != end) throw failure();
    }

    /**
     * 길이90 payload를 full-write하고 force 성공 뒤에만 메모리 전이를 승인한다.
     *
     * @param key null 불가인 현재 작업 좌표
     * @param next null 불가인 최소 기록
     * @throws IOException 실패하면 owner를 poison하고 원인 없는 고정 오류
     */
    private void append(Key key, Saved next) throws IOException {
        try {
            validate(history.get(key), next);
            byte[] payload =
                    ByteBuffer.allocate(RECORD_BYTES)
                            .putLong(key.jobKey.getMostSignificantBits())
                            .putLong(key.jobKey.getLeastSignificantBits())
                            .putLong(key.leaseGen)
                            .put(next.state)
                            .put((byte) next.attemptNo)
                            .put(HexFormat.of().parseHex(next.credentialHash))
                            .put(HexFormat.of().parseHex(next.originalHash))
                            .array();
            byte[] framed = frame(payload);
            long newEnd = Math.addExact(end, framed.length);
            if (newEnd > capacity || journal.size() != end) throw failure();
            journal.position(end);
            ByteBuffer buffer = ByteBuffer.wrap(framed);
            while (buffer.hasRemaining()) {
                if (journal.write(buffer) <= 0) throw failure();
            }
            force.apply(journal);
            end = newEnd;
            history.put(key, next);
        } catch (IOException | RuntimeException exception) {
            poisoned = true;
            throw failure();
        }
    }

    /**
     * 영구 기록의 닫힌 전이만 허용하며 이전 digest·시도를 바꾸지 않는다.
     *
     * @param prior 최초 frame에서는 null, 그 외 이전 기록
     * @param next null 불가인 다음 기록
     * @throws IOException 위반의 고정 오류
     */
    private static void validate(Saved prior, Saved next) throws IOException {
        if (prior == null) {
            if (next.state != 0 || next.attemptNo != 0 || !next.originalHash.equals("0".repeat(64)))
                throw failure();
        } else if (prior.state == 0 && next.state == 1) {
            if (next.attemptNo < 1
                    || next.attemptNo > 3
                    || !prior.credentialHash.equals(next.credentialHash)) throw failure();
        } else if (((prior.state == 0 || prior.state == 1 || prior.state == 3 || prior.state == 4)
                        && next.state == 2)
                || (prior.state == 1 && (next.state == 3 || next.state == 4))
                || (prior.state == 3 && next.state == 4)
                || (prior.state == 4 && next.state == 5)) {
            if (next.attemptNo != prior.attemptNo
                    || !next.originalHash.equals(prior.originalHash)
                    || !next.credentialHash.equals(prior.credentialHash)) throw failure();
        } else {
            throw failure();
        }
    }

    /**
     * frame 경계를 EOF까지 정확히 읽는다.
     *
     * @param buffer null 불가인 필요한 전체 길이
     * @throws IOException 잘림·읽기 정체의 고정 오류
     */
    private void readFully(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) if (journal.read(buffer) <= 0) throw failure();
    }

    /**
     * 실제 UID·정확한 mode·regularfile/directory·모든 원래 symlink 구성요소를 검사한다.
     *
     * @param path canonical 절대 경로, null 불가
     * @param directory true이면 0700 directory, false이면 0600 regularfile
     * @throws IOException 보호 위반의 고정 오류
     */
    private static void check(Path path, boolean directory) throws IOException {
        if (!path.isAbsolute() || !path.equals(path.toRealPath())) throw failure();
        Path part = path.getRoot();
        for (Path name : path) {
            part = part.resolve(name);
            if (Files.isSymbolicLink(part)) throw failure();
        }
        var attrs =
                Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if ((directory ? !attrs.isDirectory() : !attrs.isRegularFile())
                || !attrs.permissions().equals(directory ? DIRECTORY_MODE : FILE_MODE)
                || ((Number) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS))
                                .longValue()
                        != new UnixSystem().getUid()) throw failure();
    }

    /**
     * @throws IOException 닫힘·오염·실제 lock 상실이면 원인 없는 고정 거절
     */
    private void requireOpen() throws IOException {
        if (closed || poisoned || !journal.isOpen() || !lockChannel.isOpen() || !lock.isValid())
            throw failure();
    }

    /**
     * 인가가 아닌 refusal 좌표의 범위만 검사한다.
     *
     * @param jobKey null·영 UUID 불가
     * @param generation 양수 long 세대
     * @return 불변 history 키
     * @throws IOException 원인 없는 INVALID_REMOTE_JOURNAL
     */
    private static Key key(UUID jobKey, long generation) throws IOException {
        if (jobKey == null || jobKey.equals(new UUID(0, 0)) || generation <= 0) throw failure();
        return new Key(jobKey, generation);
    }

    /**
     * separator·점 경로·연속 점 없는 단일 파일명만 허용한다.
     *
     * @param name null 불가인 ASCII 1~128자 이름
     * @return 변환하지 않은 leaf
     * @throws IOException 원인 없는 INVALID_REMOTE_JOURNAL
     */
    private static String leaf(String name) throws IOException {
        if (!name.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}") || name.contains(".."))
            throw failure();
        return name;
    }

    /**
     * descriptor 문자열을 정규화 없이 읽는다.
     *
     * @param node null 불가인 descriptor 객체
     * @param key null 불가인 필드명
     * @return null·blank 아닌 원래 값
     * @throws IOException 원인 없는 INVALID_REMOTE_JOURNAL
     */
    private static String text(JsonNode node, String key) throws IOException {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw failure();
        return value.textValue();
    }

    /**
     * 소수·문자열·overflow를 거절한다.
     *
     * @param node null 불가인 정수 값
     * @return 정확한 유한 long
     * @throws IOException 원인 없는 INVALID_REMOTE_JOURNAL
     */
    private static long integer(JsonNode node) throws IOException {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) throw failure();
        return node.longValue();
    }

    /**
     * 닫힌 descriptor의 정확한 키 집합을 확인한다.
     *
     * @param node null 불가인 객체
     * @param expected null 불가인 고정 키 집합
     * @throws IOException 원인 없는 INVALID_REMOTE_JOURNAL
     */
    private static void keys(JsonNode node, Set<String> expected) throws IOException {
        if (node == null || !node.isObject()) throw failure();
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw failure();
    }

    /**
     * header 또는 고정90 payload를 완전한 frame으로 만든다.
     *
     * @param payload null 불가인 내부 payload
     * @return 길이4·payload·SHA256 32를 소유한 배열
     */
    private static byte[] frame(byte[] payload) {
        return ByteBuffer.allocate(4 + payload.length + 32)
                .putInt(payload.length)
                .put(payload)
                .put(digest(payload))
                .array();
    }

    /**
     * 복구 가능 authority가 아닌 손상 감지 checksum을 계산한다.
     *
     * @param payload null 불가인 frame payload
     * @return 원본32byte SHA256
     * @throws IllegalStateException SHA 구현을 사용할 수 없으면 원인 없는 고정 오류
     */
    private static byte[] digest(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("INVALID_REMOTE_JOURNAL");
        }
    }

    /**
     * 채널을 닫고 stable lock을 해제한다. 기록은 삭제·truncate·compact하지 않는다.
     *
     * @throws IOException release/close 실패의 고정 오류
     */
    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        boolean failed = false;
        try {
            journal.close();
        } catch (IOException exception) {
            failed = true;
        }
        try {
            lock.release();
        } catch (IOException exception) {
            failed = true;
        }
        try {
            lockChannel.close();
        } catch (IOException exception) {
            failed = true;
        }
        synchronized (OPEN) {
            OPEN.remove(lockPath);
        }
        if (failed) throw failure();
    }

    private static IOException failure() {
        return new IOException("INVALID_REMOTE_JOURNAL");
    }
}
