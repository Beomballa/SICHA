import 'package:flutter/material.dart';
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

  @override
  void initState() {
    super.initState();
    _router = GoRouter(
      initialLocation: '/invitations',
      redirect: (context, state) {
        final phase = ref.read(authProvider).phase;
        final location = state.matchedLocation;
        if (phase != AuthPhase.signedIn &&
            (location.startsWith('/invitations/') ||
                location.startsWith('/investigation/'))) {
          _returnTo = state.uri.toString();
        }
        if (phase == AuthPhase.checking || phase == AuthPhase.recovery) {
          return location == '/session' ? null : '/session';
        }
        if (phase != AuthPhase.signedIn) {
          return location == '/login' ? null : '/login';
        }
        if (location == '/login' || location == '/session') {
          final target = _returnTo ?? '/invitations';
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
