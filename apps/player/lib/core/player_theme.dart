import 'package:flutter/material.dart';

class PlayerTheme {
  static const background = Color(0xff121417);
  static const surface = Color(0xff1e2228);
  static const raised = Color(0xff252a32);
  static const text = Color(0xffe5e8eb);
  static const muted = Color(0xffb7bdc7);
  static const accent = Color(0xffffc700);
  static const border = Color(0xff69707d);

  static ThemeData get data {
    final scheme =
        ColorScheme.fromSeed(
          seedColor: accent,
          brightness: Brightness.dark,
        ).copyWith(
          surface: background,
          primary: accent,
          onPrimary: background,
          onSurface: text,
          error: const Color(0xffffa8a8),
        );
    return ThemeData(
      useMaterial3: true,
      fontFamily: 'SUIT',
      colorScheme: scheme,
      scaffoldBackgroundColor: background,
      appBarTheme: const AppBarTheme(
        backgroundColor: background,
        foregroundColor: text,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: surface,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(6),
          borderSide: const BorderSide(color: border),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(6),
          borderSide: const BorderSide(color: border),
        ),
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 14,
          vertical: 12,
        ),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          minimumSize: const Size(48, 48),
          backgroundColor: accent,
          foregroundColor: background,
          textStyle: const TextStyle(
            fontFamily: 'SUIT',
            fontSize: 16,
            fontWeight: FontWeight.w600,
          ),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size(48, 48),
          foregroundColor: text,
          side: const BorderSide(color: border),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
        ),
      ),
    );
  }
}

class PlayerPage extends StatelessWidget {
  const PlayerPage({
    super.key,
    required this.title,
    required this.children,
    this.actions,
  });
  final String title;
  final List<Widget> children;
  final List<Widget>? actions;

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(title), actions: actions),
    body: SafeArea(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 720),
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              ...children.expand(
                (child) => [child, const SizedBox(height: 16)],
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

class PlayerButton extends StatelessWidget {
  const PlayerButton(
    this.label, {
    super.key,
    required this.onPressed,
    this.secondary = false,
  });
  final String label;
  final VoidCallback? onPressed;
  final bool secondary;

  @override
  Widget build(BuildContext context) => secondary
      ? OutlinedButton(
          onPressed: onPressed,
          child: Text(label, textAlign: TextAlign.center),
        )
      : ElevatedButton(
          onPressed: onPressed,
          child: Text(label, textAlign: TextAlign.center),
        );
}

class PlayerNotice extends StatelessWidget {
  const PlayerNotice(this.message, {super.key});
  final String message;

  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: PlayerTheme.raised,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        message,
        style: const TextStyle(color: PlayerTheme.text, height: 1.5),
      ),
    ),
  );
}

class PlayerFormField extends StatelessWidget {
  const PlayerFormField({
    super.key,
    required this.label,
    required this.controller,
    this.obscure = false,
    this.keyboardType,
    this.autofillHints,
  });
  final String label;
  final TextEditingController controller;
  final bool obscure;
  final TextInputType? keyboardType;
  final Iterable<String>? autofillHints;

  @override
  Widget build(BuildContext context) => TextField(
    controller: controller,
    obscureText: obscure,
    keyboardType: keyboardType,
    autofillHints: autofillHints,
    decoration: InputDecoration(labelText: label),
  );
}
