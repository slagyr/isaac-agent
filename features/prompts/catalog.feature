Feature: Prepared-prompt catalog (commands + skills)
  Isaac discovers commands and skills from layered roots (global install +
  per-project, plus configurable extra roots), parses YAML frontmatter, and
  resolves them into one catalog. A file's kind is disambiguated by
  type: > user-invocable: > directory/filename; project entries shadow global
   ones of the same name. Bodies are loaded lazily; this feature covers the
  frontmatter index (name, type, description).

  (Timing of resolution is logged at debug — elapsed-ms + counts — to inform a
  later caching decision; debug isn't spec-asserted, so it's an acceptance
  requirement, not a scenario here.)

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: a markdown file with type command is discovered as a command
    Given the isaac file "prompts/commands/work.md" exists with:
      """
      ---
      type: command
      description: Start work on a ready bean
      params: [bean]
      ---
      Start work on bean {{bean}}. Follow the project conventions.
      """
    When the prompt catalog is resolved
    Then the prompt catalog contains:
      | name | type    | description                |
      | work | command | Start work on a ready bean |

  Scenario: a SKILL.md file with type skill is discovered as a skill
    Given the isaac file "prompts/skills/tdd/SKILL.md" exists with:
      """
      ---
      type: skill
      description: Use when writing or changing code
      ---
      Write a failing test first, then the simplest code to pass it, then refactor.
      """
    When the prompt catalog is resolved
    Then the prompt catalog contains:
      | name | type  | description                       |
      | tdd  | skill | Use when writing or changing code |

  Scenario: an explicit type overrides a conflicting directory location
    Given the isaac file "prompts/commands/helper.md" exists with:
      """
      ---
      type: skill
      description: A skill that happens to live under commands/
      ---
      Some reusable guidance.
      """
    When the prompt catalog is resolved
    Then the prompt catalog contains:
      | name   | type  | #comment                                  |
      | helper | skill | explicit type wins over the commands/ dir |
    And the log has entries matching:
      | level | event                 | name   |
      | warn  | :prompt/type-conflict | helper |

  Scenario: user-invocable provides the type for a file in a generic root
    Given config:
      | prompt-paths | ["vendor/prompts"] |
    And the isaac file "vendor/prompts/review.md" exists with:
      """
      ---
      description: Review a pull request
      user-invocable: true
      ---
      Review PR {{pr}}.
      """
    When the prompt catalog is resolved
    Then the prompt catalog contains:
      | name   | type    | #comment                                                |
      | review | command | generic root, no type: -> user-invocable decides command |

  Scenario: directory decides type when neither type nor user-invocable is present
    Given the isaac file "prompts/skills/clojure/SKILL.md" exists with:
      """
      ---
      description: Clojure conventions
      ---
      Prefer threading macros; keep functions small.
      """
    When the prompt catalog is resolved
    Then the prompt catalog contains:
      | name    | type  | #comment                                      |
      | clojure | skill | no type:/user-invocable -> skills/ dir decides |

  Scenario: a project command shadows a global command of the same name
    Given the isaac file "prompts/commands/work.md" exists with:
      """
      ---
      type: command
      description: GLOBAL work
      ---
      Global work prompt.
      """
    And the following sessions exist:
      | name   | crew | cwd         |
      | proj-s | main | target/proj |
    And the file "target/proj/prompts/commands/work.md" contains:
      """
      ---
      type: command
      description: PROJECT work
      ---
      Project work prompt.
      """
    When the prompt catalog for session "proj-s" is resolved
    Then the prompt catalog contains:
      | name | type    | description  | #comment               |
      | work | command | PROJECT work | project shadows global |

  Scenario: the project root is found by walking up from a subdirectory cwd
    Given the following sessions exist:
      | name   | crew | cwd                  |
      | deep-s | main | target/proj/src/deep |
    And the file "target/proj/prompts/commands/work.md" contains:
      """
      ---
      type: command
      description: PROJECT work
      ---
      Project work prompt.
      """
    When the prompt catalog for session "deep-s" is resolved
    Then the prompt catalog contains:
      | name | type    | description  | #comment                                  |
      | work | command | PROJECT work | root found at ancestor target/proj/prompts |

  Scenario: custom directory names map to types via prompt-dir-names config
    Given config:
      | prompt-dir-names | {"abilities" "skill"} |
    And the isaac file "prompts/abilities/refactor.md" exists with:
      """
      ---
      description: Refactoring guidance
      ---
      Make small, safe steps.
      """
    When the prompt catalog is resolved
    Then the prompt catalog contains:
      | name     | type  | #comment                              |
      | refactor | skill | abilities/ mapped to skill via config |