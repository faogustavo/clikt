package com.github.ajalt.clikt.completion

import com.github.ajalt.clikt.completion.CompletionCandidates.Custom.ShellType
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.OptionWithValues

object CompletionGenerator {

    fun generateBashCompletion(command: CliktCommand): String {
        return generateCompletion(command, zsh = false)
    }

    fun generateZshCompletion(command: CliktCommand): String {
        return generateCompletion(command, zsh = true)
    }

    internal fun generateFishCompletion(command: CliktCommand): String {
        if (command.hasFishCompletionRequirements.not())
            return ""

        return generateFishCompletionForCommand(
                command = command,
                parentCommand = null,
                parentSubcommandsVarName = "${command.commandName}_subcommands".replace('-', '_'),
                rootCommandName = command.commandName,
        )
    }

    internal fun generateCompletion(command: CliktCommand, zsh: Boolean = true): String {
        val commandName = command.commandName
        val (isTopLevel, funcName) = commandCompletionFuncName(command)
        val options = command._options
                .filterNot { it.hidden }
                .map { Triple(it.names, it.completionCandidates, it.nvalues) }
        val arguments = command._arguments.map { it.name to it.completionCandidates }
        val subcommands = command._subcommands.map { it.commandName }
        val fixedArgNameArray = command._arguments
                .takeWhile { it.nvalues > 0 }
                .flatMap { arg -> (1..arg.nvalues).map { "'${arg.name}'" } }
                .joinToString(" ")
        val varargName = command._arguments.find { it.nvalues < 0 }?.name.orEmpty()
        val paramsWithCandidates = (options.map { o -> o.first.maxByOrNull { it.length }!! to o.second } + arguments)

        if (options.isEmpty() && subcommands.isEmpty() && arguments.isEmpty()) return ""

        return buildString {
            if (isTopLevel) {
                append("""
                |#!/usr/bin/env ${if (zsh) "zsh" else "bash"}
                |# Command completion for $commandName
                |# Generated by Clikt
                |
                |
                """.trimMargin())

                if (zsh) {
                    append("""
                    |autoload bashcompinit
                    |bashcompinit
                    |
                    |
                    """.trimMargin())
                }

                append("""
                |__skip_opt_eq() {
                |    # this takes advantage of the fact that bash functions can write to local
                |    # variables in their callers
                |    (( i = i + 1 ))
                |    if [[ "${'$'}{COMP_WORDS[${'$'}i]}" == '=' ]]; then
                |          (( i = i + 1 ))
                |    fi
                |}
                |
                """.trimMargin())
            }

            // Generate functions for any custom completions
            for ((name, candidate) in paramsWithCandidates) {
                val body = (candidate as? CompletionCandidates.Custom)?.generator?.invoke(ShellType.BASH)
                        ?: continue
                val indentedBody = body.trimIndent().prependIndent("  ")
                append("""
                |
                |${customParamCompletionName(funcName, name)}() {
                |$indentedBody
                |}
                |
                """.trimMargin())
            }

            // Generate the main completion function for this command
            append("""
            |
            |$funcName() {
            |  local i=${if (isTopLevel) "1" else "$" + "1"}
            |  local in_param=''
            |  local fixed_arg_names=($fixedArgNameArray)
            |  local vararg_name='$varargName'
            |  local can_parse_options=1
            |
            |  while [[ ${'$'}{i} -lt ${'$'}COMP_CWORD ]]; do
            |    if [[ ${'$'}{can_parse_options} -eq 1 ]]; then
            |      case "${'$'}{COMP_WORDS[${'$'}i]}" in
            |        --)
            |          can_parse_options=0
            |          (( i = i + 1 ));
            |          continue
            |          ;;
            |
            """.trimMargin())

            for ((names, _, nargs) in options) {
                append("        ")
                names.joinTo(this, "|", postfix = ")\n")
                append("          __skip_opt_eq\n")
                if (nargs > 0) {
                    append("          (( i = i + $nargs ))\n")
                    append("          [[ \${i} -gt COMP_CWORD ]] && in_param='${names.maxByOrNull { it.length }}' || in_param=''\n")
                } else {
                    append("          in_param=''\n")
                }

                append("""
                |          continue
                |          ;;
                |
                """.trimMargin())
            }

            append("""
            |      esac
            |    fi
            |    case "${'$'}{COMP_WORDS[${'$'}i]}" in
            |
            """.trimMargin())

            for ((name, toks) in command.aliases()) {
                append("""
                |      $name)
                |        (( i = i + 1 ))
                |        COMP_WORDS=( "${'$'}{COMP_WORDS[@]:0:i}"
                """.trimMargin())
                toks.joinTo(this, " ", prefix = " ") { "'$it'" }
                append(""" "${'$'}{COMP_WORDS[@]:${'$'}{i}}" )""").append("\n")
                append("        (( COMP_CWORD = COMP_CWORD + ${toks.size} ))\n")

                if (!command.currentContext.allowInterspersedArgs) {
                    append("        can_parse_options=0\n")
                }

                append("        ;;\n")
            }


            for (sub in command._subcommands) {
                append("""
                |      ${sub.commandName})
                |        ${commandCompletionFuncName(sub).second} ${'$'}(( i + 1 ))
                |        return
                |        ;;
                |
                """.trimMargin())
            }

            append("""
            |      *)
            |        (( i = i + 1 ))
            |        # drop the head of the array
            |        fixed_arg_names=("${'$'}{fixed_arg_names[@]:1}")
            |
            """.trimMargin())

            if (!command.currentContext.allowInterspersedArgs) {
                append("        can_parse_options=0\n")
            }

            append("""
            |        ;;
            |    esac
            |  done
            |  local word="${'$'}{COMP_WORDS[${'$'}COMP_CWORD]}"
            |
            """.trimMargin())

            if (options.isNotEmpty()) {
                val prefixChars = options.flatMap { it.first }
                        .mapTo(mutableSetOf()) { it.first().toString() }
                        .joinToString("")
                append("""
                |  if [[ "${"$"}{word}" =~ ^[$prefixChars] ]]; then
                |    COMPREPLY=(${'$'}(compgen -W '
                """.trimMargin())
                options.flatMap { it.first }.joinTo(this, " ")
                append("""' -- "${"$"}{word}"))
                |    return
                |  fi
                |
                 """.trimMargin())
            }

            append("""
            |
            |  # We're either at an option's value, or the first remaining fixed size
            |  # arg, or the vararg if there are no fixed args left
            |  [[ -z "${"$"}{in_param}" ]] && in_param=${"$"}{fixed_arg_names[0]}
            |  [[ -z "${"$"}{in_param}" ]] && in_param=${"$"}{vararg_name}
            |
            |  case "${"$"}{in_param}" in
            |
            """.trimMargin())

            for ((name, completion) in paramsWithCandidates) {
                append("""
                |    $name)
                |
                """.trimMargin())
                when (completion) {
                    CompletionCandidates.None -> {
                    }
                    CompletionCandidates.Path -> {
                        append("       COMPREPLY=(\$(compgen -o default -- \"\${word}\"))\n")
                    }
                    CompletionCandidates.Hostname -> {
                        append("       COMPREPLY=(\$(compgen -A hostname -- \"\${word}\"))\n")
                    }
                    CompletionCandidates.Username -> {
                        append("       COMPREPLY=(\$(compgen -A user -- \"\${word}\"))\n")
                    }
                    is CompletionCandidates.Fixed -> {
                        append("      COMPREPLY=(\$(compgen -W '")
                        completion.candidates.joinTo(this, " ")
                        append("' -- \"\${word}\"))\n")
                    }
                    is CompletionCandidates.Custom -> {
                        if (completion.generator(ShellType.BASH) != null) {
                            // redirect stderr to /dev/null, because bash prints a warning that
                            // "compgen -F might not do what you expect"
                            append("       COMPREPLY=(\$(compgen -F ${customParamCompletionName(funcName, name)} 2>/dev/null))\n")
                        }
                    }
                }

                append("      ;;\n")
            }

            if (subcommands.isNotEmpty()) {
                append("""
                |    *)
                |      COMPREPLY=(${"$"}(compgen -W '
                """.trimMargin())
                subcommands.joinTo(this, " ")
                append("""' -- "${"$"}{word}"))
                |      ;;
                |
                """.trimMargin())
            }

            append("""
            |  esac
            |}
            |
            """.trimMargin())

            for (subcommand in command._subcommands) {
                append(generateCompletion(subcommand))
            }

            if (isTopLevel) {
                append("\ncomplete -F $funcName $commandName")
            }
        }
    }

    private fun commandCompletionFuncName(command: CliktCommand): Pair<Boolean, String> {
        val ancestors = generateSequence(command.currentContext) { it.parent }
                .map { it.command.commandName }
                .toList().asReversed()
        val isTopLevel = ancestors.size == 1
        val funcName = ancestors.joinToString("_", prefix = "_").replace('-', '_')
        return isTopLevel to funcName
    }

    private fun customParamCompletionName(commandFuncName: String, name: String): String {
        return "_${commandFuncName}_complete_${Regex("[^a-zA-Z0-9]").replace(name, "_")}"
    }

    private fun generateFishCompletionForCommand(
            command: CliktCommand,
            parentCommand: CliktCommand?,
            parentSubcommandsVarName: String,
            rootCommandName: String,
    ): String = buildString {
        val isTopLevel = parentCommand == null
        val commandName = command.commandName
        val parentCommandName = parentCommand?.commandName

        val options = command._options.filterNot { it.hidden }
        val subcommands = command._subcommands
        val hasSubcommands = subcommands.isNotEmpty()

        val subcommandsVarName = "${parentCommandName}_${commandName}_subcommands"
                .replace('-', '_')

        if (isTopLevel) {
            appendLine("""
                |# Command completion for $commandName
                |# Generated by Clikt
                |
            """.trimMargin())

            if (hasSubcommands) {
                val subcommandsName = subcommands.joinToString(" ") { it.commandName }
                appendLine("### Declaring root subcommands")
                appendLine("""
                    set -l ${commandName}_subcommands '$subcommandsName'
                """.trimIndent())
                appendLine()
            }
        } else {
            appendLine()
            appendLine("### Declaring $commandName")

            if (subcommands.isNotEmpty()) {
                val subcommandsName = subcommands.joinToString(" ") { it.commandName }
                appendLine("""
                    set -l $subcommandsVarName '$subcommandsName'
                """.trimIndent())
            }

            append("complete -f -c $rootCommandName ")

            if (rootCommandName == parentCommandName) {
                append("-n __fish_use_subcommand ")
            } else {
                append("-n \"__fish_seen_subcommand_from $parentCommandName; and not __fish_seen_subcommand_from \$$parentSubcommandsVarName\" ")
            }

            append("-a $commandName ")

            val help = command.commandHelp.replace("'", "\\'")
            if (help.isNotBlank()) {
                append("-d '${help}'")
            }
        }

        options.mapNotNull { option ->
            val names = option.names.shortAndLongNames
            if (names.first.isEmpty() && names.second.isEmpty())
                return@mapNotNull null

            val help = option.optionHelp.replace("'", "\\'")
            buildString {
                append("complete -f -c $rootCommandName ")

                if (isTopLevel) {
                    if (hasSubcommands) {
                        append("-n \"not __fish_seen_subcommand_from \$${commandName}_subcommands\" ")
                    }
                } else {
                    append("-n \"__fish_seen_subcommand_from $commandName\" ")
                }

                names.first.forEach {
                    append("-s $it ")
                }

                names.second.forEach {
                    append("-l $it ")
                }

                if (option is OptionWithValues<*, *, *>) {
                    append("--require-parameter ")
                }

                when (val completionCandidate = option.completionCandidates) {
                    is CompletionCandidates.None -> {
                    }
                    is CompletionCandidates.Path -> {
                        append("-a \"(__fish_complete_path)\" ")
                    }
                    is CompletionCandidates.Hostname -> {
                        append("-a \"(__fish_print_hostnames)\" ")
                    }
                    is CompletionCandidates.Username -> {
                        append("-a \"(__fish_complete_users)\" ")
                    }
                    is CompletionCandidates.Fixed -> {
                        val completeOptions = completionCandidate.candidates.joinToString(" ")
                        append("-a \"$completeOptions\" ")
                    }
                    is CompletionCandidates.Custom -> {
                        val customCompletion = completionCandidate.generator(ShellType.FISH)
                        append("-a $customCompletion ")
                    }
                }

                if (help.isNotBlank()) {
                    append("-d '$help'")
                }
            }
        }.forEachIndexed { index, optionComplete ->
            if (index == 0) {
                if (isTopLevel) {
                    appendLine("### Adding top level options")
                } else {
                    appendLine()
                }
            }

            appendLine(optionComplete)
        }

        subcommands.map { subCommand ->
            generateFishCompletionForCommand(
                    command = subCommand,
                    parentCommand = command,
                    rootCommandName = rootCommandName,
                    parentSubcommandsVarName = subcommandsVarName
            )
        }.forEach(::append)
    }

    private val CliktCommand.hasFishCompletionRequirements: Boolean
        get() = _options.flatMap { it.names }.any { it.startsWith('-') } ||
                _subcommands.isNotEmpty()

    private val Set<String>.shortAndLongNames: Pair<List<String>, List<String>>
        get() = filter { it.startsWith("-") && (it.length == 2 || it[1] == '-') }
                .partition { it.length == 2 }
                .let { data ->
                    data.first.map { it.trimStart('-') } to data.second.map { it.trimStart('-') }
                }
}
