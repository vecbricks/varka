# Task <n>: <the row's title>

*Scoped <date> (milestone <m> section <s>, row <n>); <opened, closed> <date>.*

<!-- A plan is a record written as the work happens, not a specification written before it
     and not a summary written after. Keep the sections below in this order, fill each when
     its moment comes, and never rewrite an earlier section to match a later result: add the
     correction and say what it corrects. Delete these comments. -->

## 1. The question

<!-- What is unknown or broken, in one or two paragraphs, with the measurement or the failure
     that raised it. Numbers here quote a committed results file. -->

## 2. The change

<!-- What is built: the files, the shape of the change, the alternatives considered and why
     this one. For a design with competing forms, build the arms and measure them rather than
     argue; a table of arms goes here. For a refactor or a port, the list of members it moves
     is generated with dev/varka_members.py and the seam chosen with dev/varka_callgraph.py,
     never written from memory. -->

## 3. Predictions, registered before the run

<!-- Numbered, specific, falsifiable, written before the first measurement: "the narrowed
     store reads within 3% of the wide one at 128 bits". If the task measures nothing, say
     what it will show instead and how. -->

## 4. Verification

<!-- The suites and the gate, the regenerated oracles (coverage, bytes, census, docs), the
     benchmark regeneration and its committed files. What was run, in what order, and what
     it read. -->

## 5. Outcome

<!-- Each prediction scored: held, failed, not decidable, with the numbers. What the task
     found that it did not look for goes to a new milestone row, and this section says which.
     Lessons go to sql/varka/skills/ with a pointer from here. -->

## 6. Explicitly out of this task

<!-- Work seen and deferred, with the reason and where it now lives: a later row, the next
     milestone's scope catalogue, or an upstream ticket. -->
