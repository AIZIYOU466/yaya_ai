import 'package:flutter/material.dart';

/// 毛玻璃卡片
class GlassCard extends StatelessWidget {
  final Widget child;
  final EdgeInsetsGeometry? padding;
  final EdgeInsetsGeometry? margin;
  final Color? backgroundColor;
  final double? borderRadius;
  final double? elevation;
  final VoidCallback? onTap;

  const GlassCard({
    super.key,
    required this.child,
    this.padding,
    this.margin,
    this.backgroundColor,
    this.borderRadius = 16.0,
    this.elevation = 8.0,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      margin: margin,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(borderRadius!),
          child: Container(
            padding: padding,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(borderRadius!),
              color: backgroundColor ?? colors.surfaceContainerHighest,
              border: Border.all(color: colors.outlineVariant, width: 1),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withOpacity(0.1),
                  blurRadius: elevation!,
                  offset: const Offset(0, 4),
                ),
              ],
            ),
            child: child,
          ),
        ),
      ),
    );
  }
}

/// 动画分割线
class AnimatedDivider extends StatefulWidget {
  final double width;
  final double height;
  final Color? color;
  final Duration duration;

  const AnimatedDivider({
    super.key,
    this.width = 200.0,
    this.height = 1.0,
    this.color,
    this.duration = const Duration(milliseconds: 300),
  });

  @override
  State<AnimatedDivider> createState() => _AnimatedDividerState();
}

class _AnimatedDividerState extends State<AnimatedDivider>
    with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<double> _animation;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      duration: widget.duration,
      vsync: this,
    );
    _animation = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(parent: _controller, curve: Curves.easeInOut),
    );
    _controller.forward();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _animation,
      builder: (context, child) {
        return Container(
          width: widget.width * _animation.value,
          height: widget.height,
          color: widget.color ?? Colors.grey.withOpacity(0.3),
        );
      },
    );
  }
}

/// 带蒙版的输入框
class MaskedInput extends StatefulWidget {
  final TextEditingController controller;
  final String hintText;
  final TextInputType keyboardType;
  final bool obscureText;
  final VoidCallback? onSubmitted;

  const MaskedInput({
    super.key,
    required this.controller,
    this.hintText = '',
    this.keyboardType = TextInputType.text,
    this.obscureText = false,
    this.onSubmitted,
  });

  @override
  State<MaskedInput> createState() => _MaskedInputState();
}

class _MaskedInputState extends State<MaskedInput> {
  bool _obscureText = false;

  @override
  void initState() {
    super.initState();
    _obscureText = widget.obscureText;
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      decoration: BoxDecoration(
        color: colors.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: colors.outlineVariant, width: 1),
      ),
      child: TextField(
        controller: widget.controller,
        keyboardType: widget.keyboardType,
        obscureText: _obscureText,
        onSubmitted: (value) => widget.onSubmitted?.call(),
        style: TextStyle(color: colors.onSurface),
        decoration: InputDecoration(
          hintText: widget.hintText,
          hintStyle: TextStyle(color: colors.onSurfaceVariant),
          border: InputBorder.none,
          contentPadding: const EdgeInsets.symmetric(
            horizontal: 16,
            vertical: 12,
          ),
          suffixIcon: widget.obscureText
              ? IconButton(
                  icon: Icon(
                    _obscureText ? Icons.visibility : Icons.visibility_off,
                    color: colors.onSurfaceVariant,
                  ),
                  onPressed: () {
                    setState(() {
                      _obscureText = !_obscureText;
                    });
                  },
                )
              : null,
        ),
      ),
    );
  }
}

/// 聊天气泡
class ChatBubble extends StatelessWidget {
  final String message;
  final bool isUser;
  final String? avatarUrl;
  final String? name;

  const ChatBubble({
    super.key,
    required this.message,
    this.isUser = false,
    this.avatarUrl,
    this.name,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (!isUser && avatarUrl != null)
          CircleAvatar(
            backgroundImage: NetworkImage(avatarUrl!),
            radius: 16,
          ),
        if (!isUser && avatarUrl != null) const SizedBox(width: 8),
        Expanded(
          child: Column(
            crossAxisAlignment:
                isUser ? CrossAxisAlignment.end : CrossAxisAlignment.start,
            children: [
              if (name != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Text(
                    name!,
                    style: TextStyle(
                      fontSize: 12,
                      color: Colors.grey[600],
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
              GlassCard(
                padding: const EdgeInsets.all(12),
                margin: const EdgeInsets.symmetric(horizontal: 8),
                child: Text(
                  message,
                  style: const TextStyle(
                    fontSize: 16,
                    height: 1.5,
                  ),
                ),
              ),
            ],
          ),
        ),
        if (isUser && avatarUrl != null) const SizedBox(width: 8),
        if (isUser && avatarUrl != null)
          CircleAvatar(
            backgroundImage: NetworkImage(avatarUrl!),
            radius: 16,
          ),
      ],
    );
  }
}
