import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'auth/auth_controller.dart';
import 'core/player_theme.dart';
import 'investigation/investigation.dart';
import 'lobby/invitations.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const ProviderScope(child: PlayerApp()));
}

class PlayerApp extends ConsumerStatefulWidget {
  const PlayerApp({super.key});

  @override
  ConsumerState<PlayerApp> createState() => _PlayerAppState();
}

class _PlayerAppState extends ConsumerState<PlayerApp> {
  late final GoRouter _router;
  late final ProviderSubscription<AuthSnapshot> _subscription;
  String? _returnTo;

  /// 인증 뒤에는 내부 초대·조사 경로만 복귀하며 웹 기본 확인은 계정 화면을 유지한다.
  @override
  void initState() {
    super.initState();
    // 웹 push도 실제 주소에 반영하여 새로고침이 현재 초대·조사로 복귀하게 한다.
    if (kIsWeb) GoRouter.optionURLReflectsImperativeAPIs = true;
    _router = GoRouter(
      initialLocation: kIsWeb ? '/account' : '/invitations',
      redirect: (context, state) {
        final phase = ref.read(authProvider).phase;
        final location = state.matchedLocation;
        if (phase != AuthPhase.signedIn &&
            (location == '/invitations' ||
                location.startsWith('/invitations/') ||
                location.startsWith('/investigation/'))) {
          _returnTo = location;
        }
        if (phase == AuthPhase.checking || phase == AuthPhase.recovery) {
          return location == '/session' ? null : '/session';
        }
        if (phase != AuthPhase.signedIn) {
          return location == '/login' ? null : '/login';
        }
        if (location == '/login' || location == '/session') {
          final target = _returnTo ?? (kIsWeb ? '/account' : '/invitations');
          _returnTo = null;
          return target;
        }
        return null;
      },
      routes: [
        GoRoute(
          path: '/session',
          builder: (context, state) => const SessionPage(),
        ),
        GoRoute(path: '/login', builder: (context, state) => const LoginPage()),
        if (kIsWeb)
          GoRoute(
            path: '/account',
            builder: (context, state) => const WebAccountPage(),
          ),
        GoRoute(
          path: '/invitations',
          builder: (context, state) => const InvitationListPage(),
        ),
        GoRoute(
          path: '/invitations/:testKey',
          builder: (context, state) => InvitationDetailPage(
            key: ValueKey(state.pathParameters['testKey']),
            testKey: state.pathParameters['testKey']!,
          ),
        ),
        GoRoute(
          path: '/investigation/:testKey',
          builder: (context, state) => InvestigationPage(
            key: ValueKey(state.pathParameters['testKey']),
            testKey: state.pathParameters['testKey']!,
          ),
        ),
      ],
    );
    _subscription = ref.listenManual(
      authProvider,
      (previous, next) => _router.refresh(),
    );
    Future.microtask(() => ref.read(authProvider.notifier).restore());
  }

  @override
  void dispose() {
    _subscription.close();
    _router.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp.router(
    title: '시차',
    theme: PlayerTheme.data,
    routerConfig: _router,
  );
}

/// 서버가 확인한 웹 계정과 2단계 초대 진입점을 제공한다.
class WebAccountPage extends ConsumerWidget {
  const WebAccountPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authProvider);
    return PlayerPage(
      title: '웹 계정 확인',
      children: [
        const PlayerNotice(
          '웹 2단계에서는 초대와 2인 조사를 제공합니다. 보고서·결과·피드백은 아직 제공하지 않습니다.',
        ),
        Text('현재 계정: ${auth.nickname ?? ''}'),
        if (auth.error != null) PlayerNotice(auth.error!),
        PlayerButton('초대로 이동', onPressed: () => context.go('/invitations')),
        PlayerButton(
          '로그아웃',
          onPressed: () => ref.read(authProvider.notifier).logout(),
        ),
      ],
    );
  }
}

class SessionPage extends ConsumerWidget {
  const SessionPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authProvider);
    return PlayerPage(
      title: '계정 확인',
      children: [
        if (auth.phase == AuthPhase.checking)
          const Center(child: CircularProgressIndicator()),
        if (auth.error != null) PlayerNotice(auth.error!),
        if (auth.phase == AuthPhase.recovery)
          PlayerButton(
            '인증 다시 확인',
            onPressed: () => ref.read(authProvider.notifier).retryRestore(),
          ),
      ],
    );
  }
}

class LoginPage extends ConsumerStatefulWidget {
  const LoginPage({super.key});

  @override
  ConsumerState<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends ConsumerState<LoginPage> {
  final _email = TextEditingController();
  final _password = TextEditingController();
  bool _busy = false;

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _login() async {
    if (_busy) return;
    setState(() => _busy = true);
    await ref
        .read(authProvider.notifier)
        .login(_email.text.trim(), _password.text);
    if (mounted) setState(() => _busy = false);
  }

  @override
  Widget build(BuildContext context) {
    final auth = ref.watch(authProvider);
    return PlayerPage(
      title: 'LOCAL 로그인',
      children: [
        const PlayerNotice('초대를 받은 LOCAL 계정으로 로그인해 주세요.'),
        if (auth.error != null) PlayerNotice(auth.error!),
        PlayerFormField(
          label: '이메일',
          controller: _email,
          keyboardType: TextInputType.emailAddress,
          autofillHints: const [AutofillHints.email],
        ),
        PlayerFormField(
          label: '비밀번호',
          controller: _password,
          obscure: true,
          autofillHints: const [AutofillHints.password],
        ),
        PlayerButton(_busy ? '로그인 중' : '로그인', onPressed: _busy ? null : _login),
      ],
    );
  }
}
