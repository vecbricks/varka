# Euclidean affine functions and their application to calendar algorithms

Cassio Neri (Independent Researcher, London, UK) and Lorenz Schneider (EMLYON
Business School, Lyon, France).

*Software: Practice and Experience*, 2023, 53 (4), pp. 937-970.
DOI [10.1002/spe.3172](https://doi.org/10.1002/spe.3172).
Open-access deposit: HAL [hal-04346335](https://hal.science/hal-04346335v1).

> **This is a machine transcription of a third-party paper, kept for reference.**
> It is not Varka documentation and carries no ASF licence. See `README.md` in
> this directory for the provenance and licensing note, and read the PDF rather
> than this file whenever a constant or an inequality matters.

## Why this paper is here

Varka's civil-from-days lowering (VARKA-26) follows Hinnant's `civil_from_days`.
This paper's Algorithms 5 and 6 are a different and generally better
decomposition, and `sql/varka/plans/m4/VARKA-53.md` takes the half of it that
an `IntVector` lane can express - the month block of Algorithm 5, where one
affine numerator `N_3 = 2141 * N_Y + 197913` yields both the month
(`N_3 / 2^16`) and the day of month (`N_3 % 2^16 / 2141`). Section 8.2 is also
worth reading on its own account: it is an explicit instruction-level
parallelism argument, showing that `P_2 % 2^32` and `P_2 / 2^32` carry no
dependency on each other where a classic quotient-then-remainder pair does.

## How this transcription was made, and what it loses

`pdftotext -bbox-layout` gives word boxes but no font metadata, so the plain
text layer flattens every superscript and subscript - `2^16` becomes `216`,
`N_C` becomes `NC` - which for this paper destroys the meaning of nearly every
formula. This transcription therefore rebuilds the text from the word geometry:
a glyph smaller than its line's body and off its baseline is a superscript or a
subscript, and is written `^{...}` or `_{...}`. That recovers 1408 scripts.
Seven variables the PDF had already merged into single tokens were restored by
name, guarded so that "New York, NY, USA" in the bibliography is left alone.

Known losses, in rough order of how much they matter:

* **Tall delimiters break formulas apart.** Set-builder braces, large
  parentheses and fraction bars are separate glyphs on their own lines, so
  displayed formulas that use them - most of the theorem statements in
  sections 3, 7, 13 and 14 - come through fragmented. The linear algorithm
  formulas, which is what this paper is here for, do not use them and are
  intact. **Read the PDF for the theorem statements.** A model-based converter
  (`marker-pdf`) does render them correctly and was evaluated for this reason;
  it was rejected because on the same pages it silently closed three half-open
  intervals and dropped a digit from an eleven-digit bound. `README.md` in this
  directory has that comparison.
* **Figures and tables are gone**; only their captions survive. The assembly
  listings in Figures 5-8 and the benchmark tables in section 12 are not here.
* **Section titles were restored by hand.** The PDF sets them in letter-spaced
  small capitals, which no text layer recovers as words.
* Inline citation markers are sometimes rendered as ordinary digits attached to
  the preceding word.

Every glyph is otherwise the PDF's own, including its notation: `∕` for the
quotient of Euclidean division, `%` for the remainder, `⋅` for multiplication.

---

Received: 5 March 2022

```
                    Revised: 5 October 2022

                                        Accepted: 17 October 2022
```

DOI: 10.1002/spe.3172

RESEARCH ARTICLE

Euclidean affine functions and their application to
calendar algorithms

Cassio Neri^{1}

```
                     Lorenz    Schneider^{2}
```

1

 Independent Researcher, London, UK

2

 EMLYON Business School, Lyon, France

Correspondence
Lorenz Schneider, EMLYON Business
School, Lyon, France.
Email: schneider@em-lyon.com

```
                                      Abstract
                                      In everyday  life, dates are specified in terms of year, month and day, but this is
                                      not how  digital devices represent them. Such devices continuously count elapsed

                                      days since a certain reference date, usually 1 January 1970. Accordingly, the date

                                      exactly one year after this reference is 1 January 1971 and digital devices repre-
                                      sent it as 365. Conversions between machine  and human   formats are, arguably,

                                      amongst  the most common    operations performed  by digital devices and consti-

                                      tute the subject of this article. We introduce Euclidean affine functions (EAFs)
                                      and  study their properties. EAFs are of the form f (n) = (a ⋅ n + b)∕d, where n, a,

                                      b, and d are integers and where ∕ denotes the quotient of Euclidean division. We

                                      derive algebraic relations and numerical approximations that are important for
                                      the efficient evaluation of these expressions in modern CPUs. Since division is

                                      a particular case of an EAF (when a = 1 and b = 0), the optimisations proposed

                                      in this article can also be applied to division. The main application presented in
                                      this article is the derivation of conversion algorithms for the Gregorian calendar.

                                      We  will show that they can be implemented  substantially more efficiently than

                                      is currently the case in widely used C, C++, C#, and Java open source libraries.
                                      Gains  in speed of a factor of two or more are common. These  algorithms have

                                      been  implemented  in GCC,  the Linux Kernel and .NET.

                                      KEYWORDS

                                      Euclidean affine functions, integer division, calendar algorithms
```

## 1 Introduction

The most widely used civil calendar today, adopted by almost every country in the world, is the Gregorian calendar, named
after Pope Gregory XIII who introduced it in 1582. At that time, the standard calendar in Europe was the Julian calendar,
brought in by Julius Caesar, the supreme ruler of Rome, in 45 BC.^{*} These two calendars are very similar and define exactly
the same months, from January to December. Months have the same (varying) number of days in both calendars. Years

*

 The fascinating history of calendars is beyond the scope of this article. Interested readers are directed to Richards^{1} and Duncan.^{2}

Abbreviations: EAF, Euclidean Affine Function; CPU, Central Processing Unit; GCC, The Gnu Compiler Collection.
[Correction added on 21 December 2022, after first online publication: the expanded form of CPU was corrected in this version.]

This is an open access article under the terms of the Creative Commons Attribution-NonCommercial License, which permits use, distribution and reproduction in any
medium, provided the original work is properly cited and is not used for commercial purposes.
© 2022 The Authors. Software: Practice and Experience published by John Wiley & Sons Ltd.

Softw: Pract Exper. 2023;53:937–970.

usually have 365 days, but some – called leap years as opposed to common years – have an extra day, making them
366 days long. A key difference between the Julian and Gregorian calendars, however, is the rule used to determine leap
years, as stated below.

Definition 1 ( Julian rule.). A Julian leap year is a multiple of 4.

Definition 2 (Gregorian rule.). A Gregorian leap year is a multiple of 4, except if it is divisible by 100 but not by 400.

   The next year that these rules will disagree on is 2100, a leap year in the Julian calendar but not in the Gregorian
calendar. Since 2000 was a leap year in both calendars, the most recent year they disagreed on was 1900. Generally, these
rules diverge three times in any span of 400 years, so that in such periods there are 100 Julian leap years but only 97
Gregorian leap years.
   The reason for introducing leap years is, roughly speaking, to make the average calendar year closer to an actual
astronomical value. A year^{†} is approximately 365.2424 days long, that is, a bit longer than the 365 days ascribed to common
years. Increasing the calendar year every now and then brings the average duration closer to 365.2424. Accordingly, in
each 4-year period of the Julian calendar, there are 3 common years and 1 leap year. Hence, the average duration of the
Julian year is:

```
                                        3 ⋅ 365 + 1 ⋅ 366  1461
                                                        =
                                                                = 365.2500.
                                               4
                                                            4
```

This entails an error of 365.2500 − 365.2424 = 0.0076 days per year, which might not seem too large, but which amounts
to 1 day every 0.0076^{−1} ≈ 132 years. Unsurprisingly, 16 centuries after its introduction, the misalignment between the
Julian calendar and the March equinox was noticeable enough to motivate the Gregorian reform.
   Similarly, in each 400-year period of the Gregorian calendar, there are 303 common years and 97 leap years. Hence,
the average duration of the Gregorian year is:

```
                                     303 ⋅ 365 + 97 ⋅ 366  146097
                                                         =
                                                                   = 365.2425.
                                             400
                                                             400
```

The error is 365.2425 − 365.2424 = 0.0001 days per year, or 1 day every 0.0001^{−1} = 10,000 years.^{‡}
   We are primarily interested in the Gregorian calendar, given its widespread use today. We nonetheless first derive
algorithms for the Julian calendar as the process for that calendar is simpler, providing easier examples of our results. For
the same reason, we in fact consider the Egyptian calendar^{§} first of all, before either the Julian or Gregorian calendars. The
Egyptian calendar has no leap year, so every year is 365 days long. Conversions for this calendar are covered in Section 2,
but it is no surprise that they involve division by 365. We now review previous results on division by constants before
considering the generalisations required to tackle the conversions for the Julian and Gregorian calendars.
   Divisions by constants appear in some of the most common tasks performed by software systems, including printing
decimal numbers (division by powers of 10) and working with times (division by 24, 60, and 3600). Since division is
the slowest of the four basic arithmetical operations, various authors^{4-8} have proposed strength reduction optimisations
(i.e., the replacement of instructions with alternatives that are mathematically equivalent but faster) for integer divisions
when divisors are constants known by the compiler. The algorithms proposed by Granlund and Montgomery^{6} have been
implemented by major compilers, for example.
   Remainder calculation is a closely related problem, also arising in the tasks mentioned above. Nevertheless, the cited
works do not consider optimisations for this operation. Even when only the remainder is necessary but not the quotient,
compilers are content to apply strength reduction to obtain the quotient q = n∕d first and then evaluate the expression

†

 This is the number of days between two consecutive March equinoxes. This is just one of many related, but different, astronomical definitions that in
layman’s terms is referred to as a year. The astronomy underlying calendars is yet another fascinating topic that is also beyond the scope of our paper.
For details, see Steel.^{3}
‡
 Although this clearly indicates the superiority of the Gregorian calendar over the Julian calendar in terms of aligning with the March equinox, this
number must be taken with a pinch of salt. Firstly, for the sake of simplicity we are not showing enough decimal digits and so the accuracy suffers.
Secondly, the duration of the year (in SI seconds) is not constant (and neither is the duration of a day). Over such a large time-span, it changes
noticeably. It is therefore difficult to obtain accurate results using currently known values.
§
 Actually, the Egyptian civil calendar, to distinguish it from other calendars the ancient Egyptians used. For brevity, we shall simply refer to it as the
Egyptian calendar. The exact date this calendar was introduced is not known, but it is speculated to be some time between 2937 and 2821 BC.^{1}

r = n − d ⋅ q, which gives the remainder r = n%d. For this reason, Lemire et al.^{9} and Warren^{10} considered the problem of
directly obtaining the remainder without first calculating the quotient.
   Due to the variable lengths of years and months, conversions from elapsed days (since a fixed reference date) to
dates in the Julian and Gregorian calendars cannot be tackled with a simple sequence of quotient and remainder calculations, at least not in the same form as seen for the Egyptian calendar. The method is nonetheless fairly similar.
Indeed, as we shall see in this article, this pattern is recovered in a more general setting that uses functions of the
form f (n) = (a ⋅ n + b)∕d – called Euclidean affine functions (EAFs) – of which division is a particular case (when a = 1
and b = 0).
   The previous paragraph touches on a critical point that distinguishes implementations of calendar algorithms:
how they deal with variable month and year lengths. Some implementations11,12 resort to look-up tables, which can
be costly when the L1-level cache is cold and can increase cache thrashing since the implementations compete for
cache memory against other running software. The implementations conduct linear searches on the look-up tables,
entailing branching that can cause stalls in the processor’s execution pipeline. Other implementations1,13-19 tackle the
issue entirely through EAFs. Nevertheless, they do not go far enough and do not use the mathematical properties
derived in this article. To the best of our knowledge, the closest work to make use of some of these properties is
Baum.^{13} He provides some explanations, but appears to resort to trial and error when it comes to pre-calculating certain magic numbers used in his algorithm. We go further by providing a systematic and general framework for such
calculations.
   This article expands previous works in two ways. Firstly, it considers the more general setting of EAFs. Secondly, it
suggests optimisations that are even more effective in applications where both the quotient and the remainder need to be
evaluated.
   We derive EAF-related equalities that provide alternative ways of evaluating expressions commonly used in applications. (For instance, n − d ⋅ (n∕d), which is used to obtain the remainder as explained above.) These equalities underpin
optimisations, other than strength reduction, that take into account aspects of modern CPUs. Specifically, they foster
instruction-level parallelism implemented by superscalar processors and they profit from the backward compatibility
features that drove the design of the x86_64 instruction set.
   Our Gregorian calendar algorithms are substantially faster than those in widely used open source implementations,
as shown by benchmarks against counterparts in glibc,11,20 Boost,^{17} libc++,^{18} .NET,^{12}^{¶} and OpenJDK^{21} (Android contains
the same implementations).^{22} Our algorithms are also faster than others found in the academic literature.1,13-16,19
   Our paper and its main contributions are organised as follows.
   Section 2 covers conversions for the Egyptian calendar, pinpointing the mathematical properties of division that need
to be generalised for EAFs in order to enable usage with more complex calendars. This section also motivates a twist of
the calendar, the topic of Section 4.
   Section 3 introduces EAFs and states some of their properties. Their proofs are set out later in Section 13. Although
EAFs appear in competitor algorithms, we were unable to find any systematic coverage of them. Hatcher^{15} and Richards^{1}
appear to be aware of some of the results of Theorem 1, but they do not point to any proof.
   Sections 5 and 6 make use the results of Sections 3 and 4, and derive the conversion algorithms for the Julian and
Gregorian calendars, respectively.
   Section 7 concerns efficient evaluations of EAFs. Theorems 2 and 3 generalise prior-known results on division to
EAFs. The proofs are shown in Section 14, which revisits previous results and brings geometric insights to the problem.
Theorem 5 concerns the efficient evaluation of residuals: they are to EAFs what remainders are to division. The optimisation proposed by Theorem 5 is fundamentally different from other optimisations, both in the present article and
in prior works, since it does not involve strength reduction. Indeed, this theorem shows how to break a data dependency present in the instructions currently emitted by compilers, enabling instruction-level parallelism, as we shall see
in Section 8.2.
   Section 8 applies the results of Section 7 to obtain optimised, but theoretical, versions of the algorithms for the Gregorian calendar. Sections 9 and 10 cover practical aspects that we take into consideration in Section 11 to derive practical
optimised implementations of the algorithms. Finally, Section 12 presents the performance analysis.
   We conclude this introduction by specifying the notation used throughout the article and by recalling some
well-known results.

¶
 During the revision of this article, we were informed about a .NET pull request that implements one of our algorithms. It is expected to be part of
release 7.

    Forward slash ∕ and percent % respectively denote the quotient and remainder of Euclidean division. More precisely,
given n, d ∈ Z, with d ≠ 0, there exist unique q and r ∈ Z such that 0 ≤ r < |d| and n = d ⋅ q + r. Then n∕d = q and
n%d = r.
    We give multiplication, quotient and remainder operators equal precedence. Hence, a ⋅ b%c = (a ⋅ b)%c, a%b ⋅ c =
(a%b) ⋅ c, a∕b%c = (a∕b)%c and a%b∕c = (a%b)∕c. Moreover, a + b%c = a + (b%c), and a − b%c = a − (b%c).
    The set of non-negative integer numbers is denoted by Z^{+} = {x ∈ Z ; x ≥ 0}. We will occasionally work on
non-integer numbers, but they will all be rationals, that is, elements of Q. It is important to distinguish the Euclidean
quotient n∕d (an integer number) from rational division n ⋅ d^{−1} = nd (a rational number). Obviously, if n ⋅ d^{−1} is an integer,
then n∕d = n ⋅ d^{−1}.
    For any x ∈ Q, ⌊x⌋ and ⌈x⌉ respectively denote the floor of x – the largest integer not larger than x – and the ceil of
x – the smallest integer not smaller than x.
    A date is a triplet X = (Y , M, D) where Y , M and D are the integer values of year, month and day. For the Gregorian
calendar, it is convenient to break Y down into two components: the century C and the year of the century Z, related
by:^{#}

```
                              Y =  100 ⋅ C + Z,

                                                   C = Y ∕100

                                                                  and

                                                                          Z =  Y %100.
```

    Comparisons between dates have chronological meaning. For instance, X_{1} < X_{2} means that X_{1} is before X_{2} and X_{1} ≥ X_{2}
means that X_{1} is after or on X_{2}. For X_{1} ≤ X_{2}, the interval [X_{1}, X_{2}[ is referred to as the days from X_{1} to X_{2} or simply the
days up to X_{2} when X_{1} is implied from the context. The number of elements of this interval is denoted by #[X_{1}, X_{2}[. This
definition is extended to the case X_{1} > X_{2} by #[X_{1}, X_{2}[ ∶= −#[X_{2}, X_{1}[< 0. (A negative number of days represents backwards
counting.) With a slight abuse of language, in this case we still refer to days from X_{1} to X_{2} or days up to X_{2} to refer to
the interval [X_{2}, X_{1}[.
    The epoch is a fixed reference date X_{0} set by the context and from which days are counted. For any date X, the number
of days from X_{0} to X is called its rata die.||
    Let X_{0} be a fixed epoch on a given calendar. The conversion problems of interest are formalised as follows. Given a
date X find its rata die N. Conversely, given N find the date X whose rata die is N.

## 2 Algorithms for the Egyptian calendar

In this ancient calendar, every year has 365 days split into 13 months numbered from 0 to 12.^{**} The first 12 months have
30 days each, totalling 360, and the last month contains the remaining 5 days. In each month, days are numbered from
0. Conversions on this calendar are very similar to time conversions where multiplication by 60 always converts hours to
minutes and, conversely, division by 60 always converts minutes to hours.
    Deriving algorithms for this calendar is therefore as straightforward as working with times. However, we follow a path that introduces concepts and notations requiring generalisations for usage with more complex calendars.
The Egyptian calendar provides concrete examples that we can retain in this first encounter with the concepts of
interest.
    In this section, the epoch is set to X_{0} = (0, 0, 0). Our search for a date X = (Y , M, D) whose rata die is a given N starts
with its year Y . We introduce the following functions:

y(N) : = the year Y . For the Egyptian calendar, y(N) = N∕365.
y◦(N) : = the day of the year, that is, the number of days from the first day of year Y to X. For the Egyptian calendar,
         y◦(N) = N%365 – the circle ◦ is a mnemonic referring to the circles in %.

\#

 An off-by-one cautionary note: our definition of century is 0-based, whereas in common usage it is 1-based (forward for AD years and backward for
BC years). For instance, the year 2022 belongs to the 21^{st} century but the definition above gives the century C = 2022∕100 = 20 and the year of the
century 2022%100 = 22 so that 2022 = 100 ⋅ 20 + 22 = 100 ⋅ C + Z.
||
 The term rata die is usually13,19 applied to the particular case where the epoch is, roughly speaking, 31 December 0000, but we shall use it regardless
of the epoch.
**
 Only the first 12 of these 13 periods were considered real months and they had names. The last 5-day period was outside any month and the days
were referred to as epagomenal days.^{1} However, this is not relevant to our calculations: we will refer to all periods as months and only consider their
numerical values.

FIGURE 1

```
               Illustration of Y = y(N), y◦(N) and y^{∗}(Y )
```

FIGURE 2

```
               Illustration of M = m(N_{Y} ), m◦(N_{Y} ) and m^{∗}(M)
```

y^{∗}(Y ) ∶= the number of days up to the first day of year Y , that is, the number of days in all years in [0, Y [. For the
        Egyptian calendar, y^{∗}(Y ) = 365 ⋅ Y – the symbol ∗ refers to the product operation.

   Figure 1 illustrates these quantities. The axis represents the number of days since the epoch. Hence, 0 symbolises the
epoch and N is pictured on this axis. A certain interval of values falls on the same year Y and this is depicted as a heavier
line. It starts at y^{∗}(Y ) and ends at y^{∗}(Y + 1) – the beginning of the following year. The difference N − y^{∗}(Y ) is the day of
the year y◦(N).
   By construction, the following holds:

```
                                       y(N) = Y  ⇔   y^{∗}(Y ) ≤ N < y^{∗}(Y + 1),

                                                                                                               (1)

                                               y◦(N) = N − y^{∗}(y(N)).

                                                                                                               (2)
```

For the Egyptian calendar, the above reads that N∕365 = Y if, and only if, 365 ⋅ Y ≤ N < 365 ⋅ (Y + 1), and that N%365 =
N − 365 ⋅ (N∕365). This should come as no surprise. However, let’s pretend that we don’t know about this result or
about the formulas of y, y^{∗} and y◦. We only know about Equations (1) and (2), which are generic and apply to all
calendars.
   Usually, y^{∗} is easier to find than the other functions and after we find it, Eqs. (1) and (2) help to deduce y and
y◦. For the Egyptian calendar, recall now that y^{∗}(Y ) = 365 ⋅ Y . We easily recognise that 365 ⋅ Y ≤ N < 365 ⋅ (Y + 1) if,
and only if, Y = N∕365. Hence, Equation (1) gives y(N) = N∕365. Now Equation (2) yields y◦(N) = N − 365 ⋅ (N∕365)
and thus y◦(N) = N%365. At this stage, we conclude that if N is the rata die of X = (Y , M, D) and N_{Y} is its day of the
year, then

```
                                 Y  = y(N) = N∕365

                                                     and

                                                           N_{Y} = y◦(N) = N%365.
```

   To finish, we need to find M and D given the day of the year N_{Y} . This is done in a very similar way to the previous step
and we only provide a quick overview here, rather than providing the full details. We introduce the functions m, m◦ and
m^{∗} as depicted in Figure 2. An important difference with respect to Figure 1 is that 0 now represents the first day of year
Y rather than the epoch. In particular, m^{∗}(M) is defined as the number of days in all months in [0, M[.
   The counterparts to Equations (1) and (2) are:

```
                                     m(N_{Y} ) = M ⇔ m^{∗}(M) ≤ N_{Y} < m^{∗}(M + 1),

                                    m◦(N_{Y} ) = N_{Y} − m^{∗}(m(N_{Y} )).
```

From these and m^{∗} we deduce m and m◦. For the Egyptian calendar, we have m^{∗}(M) = 30 ⋅ M and it follows that:

```
                               M  = m(N_{Y} ) = N_{Y} ∕30 and

                                                           D = m◦(N_{Y} ) = N_{Y} %30.
```

It is worth noting that had we numbered the days in a month from 1, the expression N_{Y} %30 would be off by 1.
   Regarding the opposite conversion, from the definitions of y^{∗} and m^{∗}, we find that the rata die of X is:

```
                                            N  = y^{∗}(Y ) + m^{∗}(M) + D.
```

For the Egyptian calendar, this gives N = 365 ⋅ Y + 30 ⋅ M + D.
   There is an important point worth mentioning about m^{∗}. By definition, m^{∗}(M) is the number of days up to, but excluding, month M. In particular, M(12) ignores days in month 12. These days would be accounted for by M(13), except that 13
is not a valid month and M(13) is thus not defined. (This is why m^{∗}(M + 1) is greyed-out in Figure 2: it has no meaning
for M = 12.)
   Astute readers might ask, Since m is deduced from m^{∗}, which is oblivious to month 12, why does N_{Y} ∕30 yield the
right month, even when N_{Y} represents dates in month 12? What if month 12 had a different number of days instead of 5,
would N_{Y} ∕30 still be correct?
   This mystery is solved by the realisation that the expression N_{Y} ∕30 is not deduced from m^{∗} but from the expression
30 ⋅ M, which is well defined for M = 13. It so happens that 30 ⋅ M and m^{∗}(M) agree up to M = 12 and, similarly, that
N_{Y} ∕30 and m(N_{Y} ) agree up to N_{Y} = 364.^{††} Month 12 could be longer than its ascribed 5 days provided that N_{Y} stays
below 30 ⋅ 13 = 390 (i.e., provided that the year is not longer than 390 days), otherwise N_{Y} ∕30 reaches 13 but m(N_{Y} )
cannot do so.

    TAKEAWAY POINTS

    • We have defined two triplets of functions (y, y^{∗}, y◦) and (m, m^{∗}, m◦) related to year and month, respectively.
      Generally, each triplet is denoted by (f , f^{∗}, f ◦). Conceptually, f acts as a “division” that splits a certain number
      of days into periods (years or months). Reciprocally, f^{∗} is like a “multiplication” that counts the number of
      days in a given number of periods. Finally, f ◦ behaves as a “remainder”.

    • y^{∗}(Y ) is the number of days up to year Y and m^{∗}(M) is the number of days up to month M. Generally, f^{∗}(q) is
      the number of days up to period q. Usually, f^{∗} is simple to deduce from the calendar structure.

    • From f^{∗} we have deduced f and f ◦ using:

```
                                          f (n) = q ⇔ f^{∗}(q) ≤ n < f^{∗}(q + 1),

                                         f ◦(n) = n − f^{∗}(f (n)).

                                                                                                       (3)
```

    • The date X = (Y , M, D) whose rata die is a given N is found by:

```
                                          Y = y(N),

                                                      N_{Y} = y◦(N),

                                         M  = m(N_{Y} ),

                                                       D = m◦(N_{Y} ).
```

    • Reciprocally, the rata die of X = (Y , M, D) is given by:

```
                                              N = y^{∗}(Y ) + m^{∗}(M) + D.

                                                                                                       (4)
```

    • In each month, days are numbered from 0. (Otherwise, m◦(N_{Y} ) would not give the correct day and would be
      off by 1.)

    • m^{∗} does not encode any information about the number of days in the last month.

††

 Recall that N_{Y} is 0-based and, since the year is 365 days long, N_{Y} ∈ [0,364].

## 3 Euclidean affine functions

The simplicity of the Egyptian calendar allowed us to easily find y(N) = N∕365 once we had y^{∗}(Y ) = 365 ⋅ Y . Unfortunately, the existence of leap years in the Julian and Gregorian calendars forces y^{∗} to take another form that is not just a
multiplication, as the following example shows.

Example 1  (y^{∗} for the Julian calendar). Let y^{∗}(Y ) be the number of days in all years in [0, Y [. Therefore, y^{∗}(Y ) must
exceed 365 ⋅ Y by the number of leap years in [0, Y [ or, in other words, the number of multiples of 4 in [0, Y [. It is relatively
easy to deduce (and left to the reader) that the number of multiples of 4 in [0, Y [ is (Y + 3)∕4. Therefore,

```
                                   y^{∗}(Y ) = 365 ⋅ Y + (Y + 3)∕4 = (1461 ⋅ Y + 3)∕4.

                                                                                                                (5)
```

The form taken by y^{∗} motivates the next definition.

Definition 3. A function f ∶ Z → Z is a Euclidean affine function,^{‡‡} EAF for short, if it has the form f (n) = (a ⋅ n +
b)∕d for all n ∈ Z and fixed a, b, d ∈ Z with d ≠ 0.

    When (f , f^{∗}, f ◦) is a triplet of calendrical functions as in Section 2, these three functions must obey Equation (3). If
f (q) = a^{∗} ⋅ q is just a product by a^{∗}, as for the Egyptian calendar with a^{∗} = 365, then it is easy to deduce that f (n) = n∕d
is the division by d = a^{∗} and that f ◦(n) = n%d is the remainder of the same division. As we shall see in Examples 2 and 4
(below), the following theorem covers the more general case where f^{∗} is an EAF.

 ∗

Theorem 1  (EAF Theorem). Let f^{∗}(q) = (a^{∗} ⋅ q + b^{∗})∕d^{∗} with a^{∗} ≥ d^{∗} > 0. Set a = d^{∗}, b = d^{∗} − b^{∗} − 1, d = a^{∗} and

```
                                               f (n) = (a ⋅ n + b)∕d,

                                              f ◦(n) = (a ⋅ n + b)%d∕a.
```

 Then, for n ∈ Z and q ∈ Z we have:

Proof. See Section 13.

```
                                         f (n) = q ⇔ f^{∗}(q) ≤ n < f^{∗}(q + 1),

                                                                                                                (6)

                                                f ◦(n) = n − f^{∗}(f (n)).

                                                                                                                (7)

                                                                                                                  ▪
```

    The condition a^{∗} ≥ d^{∗} above means that in f^{∗}’s expression, the multiplier is at least as large as the divisor and,
conceptually, f^{∗} acts as a multiplication. In contrast, f swaps the roles of a^{∗} and d^{∗}, and behaves like a division.
    The next examples interpret the EAF theorem in the context of calendrical calculations, generalising the arguments
seen in Section 2.

Example 2  (Year EAF). Let f^{∗}(q) be the number of days in all years from some reference year up to year q. (Similar to
y^{∗} of Section 2.) Let X = (Y , M, D) be the date that is n days after the start of this counting. Suppose that f^{∗} is an EAF and
let f and f ◦ be as in Theorem 1. We emphasise that f^{∗} has a calendrical meaning and turns out to be an EAF. In contrast,
f and f ◦ are simply the functions algebraically defined in the theorem and, up to this point, we ignore any calendrical
interpretation they might have.
    Looking at the right side of Equation (6), let q be such that f^{∗}(q) ≤ n < f^{∗}(q + 1). This means that X is on or after the
first day of year q and before the first day of year q + 1, or in other words, X is in year q, that is, q = Y . Equation (6) says
that this is equivalent to f (n) = q = Y . We have just deduced the calendrical meaning of f (n): it is the year in which a date
n days after the start of the counting falls. Moreover, n − f^{∗}(f (n)) = n − f^{∗}(q) is the number of days from the first day of
year q up to X or, in other words, it is the day of the year. Therefore, Equation (7) says that this number matches f ◦(n),
that is, f ◦(n) is the day of the year of the date that is n days after the start of the counting.

‡‡

 This terminology is ours. The analogues of EAFs in higher dimensions appear in Discrete Geometry and are called quasi-affine transformations.
That area focuses on periodicity, tiling and other geometric aspects, whereas we are interested in efficient calculations. We therefore use the term EAF
to distinguish between these two approaches.

   The following is a more concrete version of the above for the Julian calendar.

Example 3  (Year EAF for the Julian calendar). As seen in Example 1, for the Julian calendar, the number of days in
years in [0, Y [ is y^{∗}(Y ) = (1461 ⋅ Y + 3)∕4. Therefore, Theorem 1 for f^{∗} = y^{∗} with constants a^{∗} = 1461, b^{∗} = 3 and d^{∗} = 4
yields a = 4, b = 0, d = 1461 and the functions y(N) = 4 ⋅ N∕1461 and y◦(N) = 4 ⋅ N%1461∕4 respectively give the year
and the day of the year of the date N days after the first day of year 0.

   What Example 2 did for years, the next one does for months.

Example 4  (Month EAF). Let f^{∗}(q) be the number of days in all months from the first day of the year up to month q.
(Similar to m^{∗} of Section 2.) Let X = (Y , M, D) be the date whose day of the year is N_{Y} ∈ [0,365[. Suppose that f^{∗} is an
EAF and let f and f ◦ be as in Theorem 1. The same reasoning used in Example 2 reveals that if f^{∗}(q) ≤ N_{Y} < f^{∗}(q + 1),
then X falls on month q, that is, q = M. From Equation (6), this is equivalent to f (N_{Y} ) = q = M and f (N_{Y} ) is thus the
month of the date whose day of the year is N_{Y} . Now, Equation (7) gives f ◦(N_{Y} ) = N_{Y} − f^{∗}(f (N_{Y} )) = N_{Y} − f^{∗}(M), which is
the number of days from the beginning of the month to X, that is, f ◦(N_{Y} ) = D.

   To finish this section, we discuss other points motivated by Theorem 1. Its statement presents the equations to obtain
a, b and d – the coefficients of f – from a^{∗}, b^{∗} and d^{∗} – those of f^{∗}. Reciprocally, we can use the same equations to solve
for a^{∗}, b^{∗} and d^{∗} to obtain a^{∗} = d, b^{∗} = a − b − 1 and d^{∗} = a. Therefore, the coefficients of f can be used to find those
of f^{∗}. Furthermore, given q ∈ Z, Equation (6) states that the set of solutions of f (n) = q is an interval whose smallest
element is f^{∗}(q) or, in other words, n = f^{∗}(q) is the minimal solution of f (n) = q. This discussion motivates the following
two definitions.

Definition 4. The minimal right inverse of f (n) = (a ⋅ n + b)∕d, with d ≥ a > 0, is defined by f^{∗}(q) = (a^{∗} ⋅ q + b^{∗})∕d^{∗},
where a^{∗} = d, b^{∗} = a − b − 1 and d^{∗} = a.

Definition 5. The residual function of f (n) = (a ⋅ n + b)∕d is defined by f ◦(n) = (a ⋅ n + b)%d∕a.

## 4 The computational calendar

February – the second month of the Julian and Gregorian calendars – is the ugly duckling of the months. It is shorter
than the others and, worse, its number of days depends on whether the year is a leap year or a common year and thus
m^{∗}(M) – the number of days in all months up to M – cannot be the same for leap and common years. For this reason, some
implementations12,20 resort to two look-up tables, one for leap years and another for common years, to store the values
of m^{∗}(M) for M ∈ {1, … , 12}. As we shall see in this section, by a twist of the calendar, we get a new calendar where m^{∗}
can be stored by a single table. Even better, we do not need a table at all.
   As discussed in Section 2, m^{∗} does not encode any information on the last month. In addition, provided it is not too
long, the last month can have different numbers of days. This suggests that things would be easier if February were the
last month of the year.^{§§} Fortunately, as far as algorithms are concerned, we do not need to have the powers of a Roman
dictator or a Pope to “reform” the calendar and move February to the end of the year. Indeed, this is what German
mathematician Christian Zeller^{23} did in 1882 for his algorithm (known as Zeller’s congruence), which finds the day of
the week for any given date.^{¶¶} He introduced a mathematically convenient calendar that is easily mapped to the Julian
and Gregorian calendars. Borrowing Hatcher’s15,16 terminology, we call it the computational calendar.
   In the computational calendar, December of year Y is followed by January and February of the same year (in contrast
to the Gregorian/Julian calendars in which they fall in year Y + 1). Accordingly, January and February are months 13
and 14 of year Y . The following March is the first month of year Y + 1.
   Figure 3 illustrates the relationships between the Gregorian/Julian and computational calendars for years 0 and 1.
There is no difference from March (M = M_{G∕J} = 3) to December (M = M_{G∕J} = 12). For February, M_{G∕J} = 2 of year 1
(generally, Y + 1) of the Gregorian/Julian calendar corresponds to M = 14 of year 0 (generally, Y ) of the computational
calendar. Therefore, the computational calendar’s leap year rule is off-by-one with respect to the Gregorian/Julian calendars. In other words, Y is a leap year in the computational calendar if, and only if, Y + 1 is a leap year in the corresponding

§§

 According to some accounts,^{1} this was the case until the Decemviri – the ten men who first penned the Roman code of laws – moved February to its
current position in 450 BC.
¶¶
  In essence, his algorithm calculates the rata die modulus 7.

FIGURE 3

```
               Years 0 and 1 in the Gregorian/Julian and computational calendars
```

Gregorian/Julian calendar. Since the rules for the Julian and Gregorian calendars differ (Definitions 1 and 2), there are
two corresponding rules on the computational calendar.

Definition 6 (Computational Julian rule). Year Y is a computational Julian leap year if Y + 1 is a multiple of 4.

Definition 7 (Computational Gregorian rule). Year Y is a computational Gregorian leap year if Y + 1 is a multiple
of 4, except if Y + 1 is divisible by 100 but not by 400.

   For each month M  ∈ {3, … , 14}, Table 1 shows its name, number of days and accumulated number of days in prior
months, that is, m^{∗}(M). As intended, m^{∗} does not depend on whether the year is a leap year or common year: the number
of days in February is irrelevant.
   The following equations map a date X = (Y , M, D) of the computational calendar into its corresponding date X_{G∕J} =
(Y_{G∕J} , M_{G∕J} , D_{G∕J} ) on the Gregorian/Julian calendar:

```
               Y_{G∕J} = Y + 1{M≥13},

                                                M_{G∕J} = M − 12 ⋅ 1{M≥13},

                                                                                      D_{G∕J} = D + 1,

                                                                                                               (8)

                                                                                      D  = D_{G∕J} − 1,

                                                                                                               (9)
```

where 1{M≥13} takes the value 1 if M ≥ 13 and 0 otherwise. The reciprocal map is:

              Y = Y_{G∕J} + 1{M_{G∕J} ≤2},

```
                                                M =  M_{G∕J} + 12 ⋅ 1{M_{G∕J} ≤2},
```

where 1_{{M}_{G∕J} ≤2} takes the value 1 if M_{G∕J} ≤ 2 and 0 otherwise.

Remark 1. The mathematically succinct expressions 1{M≥13} and 1_{{M}_{G∕J} ≤2} indicate if the month is January or February,
but there are alternative ways of determining this. For instance, Table 1 shows that the month is January or February if,
and only if, m^{∗}(M) ≥ 306. Therefore, a date whose day of the year (in the computational calendar) is N_{Y} falls in January
or February if, and only if, N_{Y} ≥ 306.

   Note, from D_{G∕J} = D + 1, that we set the computational calendar to number days from zero, which is a convenient
feature already seen in Section 2.

T A B L E 1 Months of the computational calendar

  M

```
                                 Name

                                                                      # days

                                                                                                          m∗(M)
```

  3

```
                                 March

                                                                      31

                                                                                                          0
```

  4

```
                                 April

                                                                      30

                                                                                                          31
```

  5

```
                                 May

                                                                      31

                                                                                                          61
```

  6

```
                                 June

                                                                      30

                                                                                                          92
```

  7

```
                                 July

                                                                      31

                                                                                                          122
```

  8

```
                                 August

                                                                      31

                                                                                                          153
```

  9

```
                                 September

                                                                      30

                                                                                                          184
```

  10

```
                                 October

                                                                      31

                                                                                                          214
```

  11

```
                                 November

                                                                      30

                                                                                                          245
```

  12

```
                                 December

                                                                      31

                                                                                                          275
```

  13

```
                                 January

                                                                      31

                                                                                                          306
```

  14

```
                                 February

                                                                      28 or 29

                                                                                                          337
```

Algorithm 1. Finds the Julian date (Y_{J} , M_{J} , D_{J} ) from its rata die N. (The epoch is (0, 3, 1) of the Julian calendar.)

```
                    N_{1} = 4 ⋅ N + 3,
                    Y   = N_{1}∕1461,
                    N_{Y} = N_{1}%1461∕4,

                                                 N_{2} = 5 ⋅ N_{Y} + 461,
                                                 M   = N_{2}∕153,
                                                 D   = N_{2}%153∕5,

                                                                               J   = 1{M≥13},
                                                                               Y_{J} = Y + J,
                                                                               M_{J} = M − 12 ⋅ J,
                                                                               D_{J} = D + 1.
```

    Table 1 shows that, except for M = 14, the number of days is periodic (the pattern 31 − 30 − 31 − 30 − 31 repeats twice
and starts again at M = 13) and there are 153 days in each 5-month period. This suggests the possibility that m^{∗} is the
EAF m^{∗}(M) = (153 ⋅ M + b^{∗})∕5 for some b^{∗}. By trial-and-error (a small program can perform this uninspiring task), we
find that b^{∗} = −457 works and m^{∗} becomes:^{##}

```
                                     m^{∗}(M) = (153 ⋅ M − 457)∕5,

                                                                  ∀M  ∈ [3, 14].

                                                                                                               (10)
```

This equation eliminates the need to store m^{∗}(M) in a look-up table.

Example 5  (Month EAF of the computational calendar). As seen in Example 4, we can find M and D from the day of
the year N_{Y} by applying Theorem 1 to f^{∗} = m^{∗}. We have a^{∗} = 153, b^{∗} = −457 and d^{∗} = 5, which yields a = 5, b = 461,
d = 153, and

```
                  M = m(N_{Y} ) = (5 ⋅ N_{Y} + 461)∕153
```

5

```
                                                      and

                                                              D =  m◦(N_{Y} ) = (5 ⋅ N_{Y} + 461)%153∕5.
```

## 5 Algorithms for the Julian calendar

This section derives algorithms for the Julian calendar, building up arguments that will be used for the corresponding
derivations for the Gregorian calendar in Section 6. To find a Julian date from its rata die, our algorithm first finds the
corresponding date in the computational calendar and then maps the result to the Julian calendar. On the computational
calendar, we follow the steps set out in Section 2 and Examples 2 and 4.
    The epoch is set to X_{0} = (0, 3, 0), which is the first day of the year 0 of the computational calendar.
    The derivation of y^{∗} is similar to that of Equation (5), but must account for the correct leap year rule given by
Definition 6. The number of computational leap years in [0, Y [ matches the number of multiples of 4 in [1, Y + 1[ = [1, Y ],
which is Y ∕4. Therefore, the number of days in all years in [0, Y [ is:

```
                                        y^{∗}(Y ) = 365 ⋅ Y + Y ∕4 = 1461 ⋅ Y ∕4.

                                                                                                               (11)
```

Example 6  (Year EAF for the computational Julian calendar). Let N be the rata die of X = (Y , M, D). As seen in
Example 2, we can find the year Y and day of the year N_{Y} from N by applying Theorem 1 to f^{∗} = y^{∗}. [Correction added
on 21 December 2022, after first online publication: the Theorem number has been corrected in the preceding sentence.]
We have a^{∗} = 1461, b^{∗} = 0, d^{∗} = 4, which yields a = 4, b = 3, d = 1461, as well as:

```
                     Y  = y(N) = (4 ⋅ N + 3)∕1461

                                                     and

                                                             N_{Y} = y◦(N) = (4 ⋅ N + 3)%1461∕4.
```

    We have already found M and D from N_{Y} in Example 5, that is, M = (5 ⋅ N_{Y} + 461)∕153 and D = (5 ⋅ N_{Y} + 461)%153∕5.
    Putting everything together gives Algorithm 1. It finds the Julian date (Y_{J} , M_{J} , D_{J} ) from its rata die N. Its first two
columns work on the computational calendar and calculate the year Y , day of the year N_{Y} , month M and day D. The
third column maps the date (Y , M, D) of the computational calendar to the date (Y_{J} , M_{J} , D_{J} ) of the Julian calendar using
Equation (8).

\##

  Variations of Equation (10) have been found by many authors,1,13,15-19 most often through trial-and-error. The periodicity observations above shed
some light on this matter. However, more structured methods are available.^{24} For instance, a simple textbook linear regression on the set
{

```
                              }
```

 (M, m^{∗}(M) + 0.5) ∈ Q^{2} ; M ∈ [3, 14] finds m^{∗}(M) = (26256 ⋅ M − 78317)∕858. Actually, the final version of our algorithm will use neither. As we
will see in Section 7, for a given EAF, a faster-to-evaluate alternative might exist. Using either of these two EAFs for m^{∗} allows us to find the very same
faster EAF that we will use.

Algorithm 2. Calculates the rata die N of the Julian date (Y_{J} , M_{J} , D_{J} ) (The epoch is (0, 3, 1) of the Julian calendar)

```
                                J  = 1{M_{J} ≤2},
                                Y  = Y_{J} − J,
                                M  = M_{J} + 12 ⋅ J,
                                D  = D_{J} − 1.

                                                           y^{∗} = 1461 ⋅ Y ∕4,
                                                           m^{∗} = (153 ⋅ M − 457)∕5,
                                                           N  = y^{∗} + m^{∗} + D.
```

F I G U R E 4 On the left, the month of September 1752 in Britain and its colonies when they moved from the Julian calendar (up to the
2^{nd}) to the Gregorian (from the 14^{th}). On the right, the Gregorian calendar for the same month

    Conversely, Algorithm 2 calculates the rata die N of a given Julian date (Y_{J} , M_{J} , D_{J} ). The first column maps the given
date to the date (Y , M, D) of the computational calendar. The second column calculates N using Equations (4), (10),
and (11).

## 6 Algorithms for the Gregorian calendar

Although it is now used by almost every country in the world, the adoption of the Gregorian calendar was gradual. For
instance, Britain and its colonies (including the US) only moved away from the Julian calendar in September 1752. Users
of the util-linux software package can check this historical moment on the command line as seen on the left of Figure 4.
    Eleven days from the 3rd to the 13th of September 1752 were skipped to synchronise the newly adopted calendar with
other countries. British readers will consider the cal program historically accurate, but readers from another country
might disagree. What everyone can agree on is that it is meaningless to refer to Gregorian dates prior to 1582, because
this calendar did not exist before the reform of Pope Gregory XIII.
    To avoid idiosyncrasies relating to adoption date, most implementations extrapolate the Gregorian calendar backwards
and forwards indefinitely, yielding the so called proleptic Gregorian calendar.^{1,3} The Linux utility cal also displays this
calendar, as seen on the right of Figure 4. For the sake of brevity, we drop the adjective proleptic and refer to the Gregorian
calendar even when dealing with dates prior to 1582.
    As in Section 5, to find a Gregorian date from its rata die, our algorithm first finds the date in the computational
calendar and then maps the result to the Gregorian calendar. The epoch is set to X_{0} = (0, 3, 0), which is the first day of
year 0 of the computational calendar.
    Unfortunately, the steps set out in Section 2 do not work here. Indeed, similarly to the derivation of Equation (11) but
using the computational Gregorian rule (Definition 7), we can deduce that the number of computational leap years in
[0, Y [ matches the number of years in [1, Y ] that are multiples of 4 except those that are divisible by 100 but not by 400,
which is Y ∕4 − Y ∕100 + Y ∕400. Therefore, the number of days in all years in [0, Y [ is:

```
                                     y^{∗}(Y ) = 365 ⋅ Y + Y ∕4 − Y ∕100 + Y ∕400.
```

Sadly, this expression is not an EAF|||| and we cannot apply Theorem 1.

||||

  For rational division we have

```
                           365 ⋅ Y +

                                   Y
                                        Y
                                             Y
                                                  365 ⋅ 400 ⋅ Y + 100 ⋅ Y − 4 ⋅ Y + Y
                                                                              146097 ⋅ Y
                                     −
                                          +
                                                =
                                                                            =
                                                                                      .
                                   4
                                       100  400
                                                             400
                                                                                400

                                                                                                              (12)
```

   Not all hope is lost, however. For the Egyptian and Julian calendars, we proceeded in two steps related to year and
month and applied Theorem 1 for y^{∗} and m^{∗}. For the Gregorian calendar, we simply need to add a step for the century, as
we shall see now.
   Let C be a century in the computational Gregorian calendar. From its first year, 100 ⋅ C, to its last, 100 ⋅ C + 99,
there are 100∕4 = 25 values of Y such that Y + 1 is a multiple of 4: namely, 100 ⋅ C + 3, 100 ⋅ C + 7, 100 ⋅ C + 11, … ,
100 ⋅ C + 99. For all these Y s except the last, Y + 1 is not a multiple of 100 and thus, from Definition 7, it is guaranteed to be a leap year. Hence, a century has at least 24 leap years and therefore at least 36524 days. However, a century
might have 36525 days if 100 ⋅ C + 99 turns out to be a leap year. In this article, a century with 36525 days is called a
leap century.
   Now, for Y = 100 ⋅ C + 99 we have Y + 1 = 100 ⋅ (C + 1) and this is a multiple of 400 if, and only if, C + 1 is a multiple
of 4. Hence, the number of leap centuries in [0, C[ matches the number of multiples of 4 in [1, C + 1[ = [1, C], which is
C∕4. Therefore, the number of days in all centuries in [0, C[ is:

```
                                     c^{∗}(C) = 36524 ⋅ C + C∕4 = 146097 ⋅ C∕4.

                                                                                                             (13)
```

   Having previously seen that algebraic manipulations similar to the above cannot be applied to y^{∗} to obtain an EAF,
some readers might wonder if/why Equations (11) and (13) are correct. Accordingly, we provide a step-by-step proof of
Equation (13):

              146097 ⋅ C = 146096 ⋅ C + C

```
                                       [
                                                      ]
                        = 146096 ⋅ C +  4 ⋅ (C∕4) + C%4
                             [
                                             ]
                        = 4 ⋅ 36524 ⋅ C + C∕4 + [C%4]

                                                                     by Euclidean division of C by 4

                                                                     taking out common  factor 4.
```

Since 0 ≤ C%4 < 4, by Euclidean division, the last equality above means that the two terms inside the brackets are respectively the quotient and remainder of the division of 146097 ⋅ C by 4. In particular, 146097 ⋅ C∕4 = 36524 ⋅ C + C∕4, which
is Equation (13). A similar argument holds for Equation (11).

Example 7 (Century EAF for the computational Gregorian calendar). Using Equation (13), we can derive expressions
for the century C = f (N) and the day of the century N_{C} = f ◦(N) from the rata die N. We use again the pattern that we
already encountered (twice) on the Egyptian calendar. This is the same pattern we saw in abstract terms in Examples 2
and 4, which then became more concrete in Examples 5 and 6.
   Let N be the rata die of X = (Y , M, D). Theorem 1 applied to f^{∗} = c^{∗}, shows that c(N) = (4 ⋅ N + 3)∕146097 and c◦(N) =
(4 ⋅ N + 3)%146097∕4 respectively give the century C = Y ∕100 and the day of the century N_{C} .

   We must now find Z = Y %100, M and D from the day of the century N_{C} . Luckily, this is what Algorithm 1 does if we
replace N with N_{C} and Y with Z. Indeed, by definition, the day of the century N_{C} is smaller than the number of days in
century C. Hence, N_{C} does not cross a century boundary and, in this case, the Julian and Gregorian leap years match. The
steps of Algorithm 1 can therefore be followed.
   From the discussion above, we obtain Algorithm 3, which finds the Gregorian date (Y_{G}, M_{G}, D_{G}) from its
rata die N. Its first three columns work on the computational calendar and calculate the century C, day of
the century N_{C} , year of the century Z, day of the year N_{Y} , year Y , month M and day D. The fourth column
maps the date (Y , M, D) of the computational calendar to the date (Y_{G}, M_{G}, D_{G}) of the Gregorian calendar using
Equation (8).

   Recall that Equation (12) is unfortunately not an EAF. It is correct though, and we shall use it in a slightly different
form:

```
                               y^{∗}(Y ) = 1461 ⋅ Y ∕4 − C + C∕4,

                                                              where   C =  Y ∕100.
```

Hence, although wrong, it is not irrational (forgive the pun) to expect that:

```
                                     365 ⋅ Y + Y ∕4 − Y ∕100 + Y ∕400 = 146097 ⋅ Y ∕400.
```

A counter-example is Y = 4, for which the left side gives 1461 and the right side gives 1460.

```
                                                                                                             (14)
```

Algorithm 3. Finds the Gregorian date (Y_{G}, M_{G}, D_{G}) from its rata dieN (The epoch is (0, 3, 1) of the Gregorian calendar)

     N_{1} = 4 ⋅ N + 3,
     C =  N_{1}∕146097,
     N_{C} = N_{1}%146097∕4,

```
                                   N_{2} = 4 ⋅ N_{C} + 3,
                                   Z   = N_{2}∕1461,
                                   N_{Y} = N_{2}%1461∕4,
                                   Y   = 100 ⋅ C + Z,

                                                                N_{3} = 5 ⋅ N_{Y} + 461,
                                                                M =  N_{3}∕153,
                                                                D =  N_{3}%153∕5,

                                                                                            J   = 1{M≥13},
                                                                                            Y_{G} = Y + J,
                                                                                            M_{G} = M − 12 ⋅ J,
                                                                                            D_{G} = D + 1.
```

Algorithm 4. Calculates the rata die N of the Gregorian date (Y_{G}, M_{G}, D_{G}) (The epoch is (0, 3, 1) of the Gregorian
calendar)

```
                              J = 1{M_{G}≤2},
                              Y = Y_{G} − J,
                              M = M_{G} + 12 ⋅ J,
                              D = D_{G} − 1,
                              C = Y ∕100,

                                                         y^{∗} = 1461 ⋅ Y ∕4 − C + C∕4,
                                                         m^{∗} = (153 ⋅ M − 457)∕5,
                                                         N  = y^{∗} + m^{∗} + D.
```

The first term replaces 365 ⋅ Y + Y ∕4 (as seen in Equation (11)). Moreover, using Y ∕400 = (Y ∕100)∕4 allows us to
substitute the division by 400 with a division by 4, which is much cheaper.
   Algorithm 4 calculates the rata die N of a given Gregorian date (Y_{G}, M_{G}, D_{G}). The first column maps the given date
to the date (Y , M, D) of the computational calendar and also finds the century C. The second column calculates N using
Eqs. (4), (10) and (14).

   Algorithms 3 and 4 are purely arithmetic. They avoid loops and look-up tables and hence minimise branching and
cache thrashing. They can be further optimised, however. These optimisations are the subject of next section.

## 7 Fast evaluation of Euclidean affine functions

This section covers optimisations for f (n) = (a ⋅ n + b)∕d and f ◦(n) = (a ⋅ n + b)%d∕a. For f , the particular case a = 1 and
b = 0 has been considered by many authors,^{4-8} who have derived strength reductions, including reducing n∕d to a multiplication and cheaper operations. Major optimisers use the algorithms of Granlund and Montgomery.^{6} Faster alternatives
exist but compilers are not able to use them. For instance, n might be restricted to a small interval but optimisers, unaware
of this fact, must assume that n can take any allowed value for its type, usually in an interval of form [0, 2^{w}[ or [−2^{w−1}, 2^{w−1}[.
We change focus and instead of looking for an algorithm on a given interval, we start with the best algorithm we know
and search the largest interval on which it can be applied. If such an interval is satisfactory for our application, we then
use this algorithm.
   Conceptually, to evaluate (a ⋅ n + b)∕d we multiply n by a ⋅ d^{−1} and add the result to b ⋅ d^{−1}. In this paragraph, we
assume that b = 0. We take an integer approximation a^{′} of 2^{k} ⋅ a ⋅ d^{−1}, where k ∈ Z^{+} is carefully chosen, and evaluate
a^{′} ⋅ n∕2^{k} . If the approximation a^{′} is good enough and n is not too large, so that the approximation error amplified by
the multiplication by n is still small, then we might get a ⋅ n∕d = a^{′} ⋅ n∕2^{k} . The latter expression has the advantage of

```
                                                                                                 ⌈
                                                                                                           ⌉
```

having a power-of-two divisor and is thus much cheaper to evaluate. Two natural choices for a^{′} are 2^{k} ⋅ a ⋅ d^{−1} and
⌊ _{k}
           ⌋
 2 ⋅ a ⋅ d^{−1} . In general, 2^{k} ⋅ a ⋅ d^{−1} is not an integer and thus, for positive operands, rounding up and down respectively
gives a^{′} = 2^{k} ⋅ a∕d + 1 and a^{′} = 2^{k} ⋅ a∕d. Each of these two approximations is covered by one of the following theorems.
(Their proofs are presented later in Section 14.1.)

Theorem 2  (Fast round-up EAF evaluation). Let k ∈ Z^{+} and f (n) = (a ⋅ n + b)∕d with d > 0. Set a^{′} = 2^{k} ⋅ a∕d + 1, b^{′} =
      {

```
                                 }
```

− min a^{′} ⋅ n − 2^{k} ⋅ f (n) ; n ∈ [0, d[ and 𝜀 = d − 2^{k} ⋅ a%d. For n ∈ [0, d[ define:

```
                           {
                                                                     }
                Q(n) = min  q ∈ Z^{+} ; 𝜀 ⋅ q ≥ 2^{k} ⋅ (1 + f (n)) − (a^{′} ⋅ n + b^{′})

                                                                         and
```

Let U = min{P(n) ; n ∈ [0, d[ }. Then,

```
                                                 (
                                                           )
                                   (a ⋅ n + b)∕d = a^{′} ⋅ n + b^{′} ∕2^{k} ,

                                                                   ∀n ∈ [0, U[.

                                                                               P(n) = d ⋅ Q(n) + n.
```

```
                                                                                                                ▪
```

Proof. See Section 14.1

Theorem 3  (Fast round-down EAF evaluation). Let k ∈ Z^{+} and f (n) = (a ⋅ n + b)∕d with d > 0 and 2^{k} ⋅ a%d > 0. Set

```
                         {
                                                             }
```

a^{′} = 2^{k} ⋅ a∕d and b^{′} = min 2^{k} − 1 − a^{′} ⋅ n + 2^{k} ⋅ f (n) ; n ∈ [0, d[ and 𝜀 = 2^{k} ⋅ a%d. For n ∈ [0, d[ define:

```
                             {
                                                                   }
                  Q(n) = min   q ∈ Z^{+} ; 𝜀 ⋅ q > (a^{′} ⋅ n + b^{′}) − 2^{k} ⋅ f (n)

                                                                       and

                                                                             P(n) = d ⋅ Q(n) + n.
```

 Let U = min{P(n) ; n ∈ [0, d[ }. Then,

```
                                   (a ⋅ n + b)∕d = (a^{′} ⋅ n + b^{′})∕2^{k} ,

                                                                   ∀n ∈ [0, U[.

                                                                                                                ▪
```

Proof. See Section 14.1

   In both theorems above, a^{′} and 𝜀 are obtained in O(1) operations and b^{′} is found by an O(d) search.^{***} Q(n) is essentially
obtained by a division by 𝜀, which has O(1) complexity, and consequently P(n) also has O(1) complexity. Hence, U and
the overall search for a fast EAF have O(d) time complexity. Since many EAFs featuring in calendrical calculations have
small divisors, finding more efficient alternatives for them is reasonably fast.
   The constants a^{′}, b^{′} and the upper bound U depend on the choice of k and whether we use Theorem 2 or Theorem 3.
As a comparison, the following examples show what the above theorems give for the same EAF and for the same k.

Example 8  (Fast round-up evaluation of m^{∗} for the computational calendar). The number of days in months [3, M[
of the computational calendar is given by Equation (10): m^{∗}(M) = (153 ⋅ M − 457)∕5. Theorem 2 applied to k = 5 and
f = m^{∗}, that is, for a = 153, b = −457 and d = 5, yields a^{′} = 980, b^{′} = 2928, U = 12 and

```
                              (153 ⋅ M − 457)∕5 = (980 ⋅ M − 2928)∕2^{5},

                                                                       ∀M  ∈ [0, 12[.

                                                                                                             (15)
```

Example 9 (Fast round-down evaluation of m^{∗} for the computational calendar). The number of days in months [3, M[
of the computational calendar is given by Equation (10): M = m^{∗}(M) = (153 ⋅ M − 457)∕5. Theorem 3 applied to k = 5
and f = m^{∗}, that is, for a = 153, b = −457 and d = 5, yields a^{′} = 979, b^{′} = −2919, U = 34 and

```
                              (153 ⋅ M − 457)∕5 = (979 ⋅ M − 2919)∕2^{5}, ∀M ∈ [0, 34[.
                                                                                                             (16)
```

   These examples give two fast alternatives for the same EAF. In this case, the latter has the advantage of being valid
over a larger range, [0, 34[, as opposed to [0, 12[, but in other cases the opposite is true. In our application, M is a month of
the computational calendar and belongs to the interval [3, 14]. Hence, the expression on the right side of Equation (15) is
of no use to us since it does not give the expected result for M ∈ {12, 13, 14} as calculated by the left side of the equation.
   The choice of k = 5 is not totally arbitrary: it is the minimum value for which Theorem 3 yields a faster alternative
evaluation for m^{∗} on an interval that contains [3, 14]. Larger values of k can also be used, but a^{′} is non-decreasing on k
and we want to avoid it being too large. (More on that in Section 8.)

Example 10 (Fast round-down evaluation of m for the computational calendar). The month of the date whose day of
the year is N_{Y} is given in Example 5: M = m(N_{Y} ) = (5 ⋅ N_{Y} + 461)∕153. Theorem 3 applied to k = 16 and f = m, that is,
for a = 5, b = 461 and d = 153 yields a^{′} = 2141, b^{′} = 197913, U = 734 and

```
                          (5 ⋅ N_{Y} + 461)∕153 = (2141 ⋅ N_{Y} + 197913)∕2^{16}, ∀N_{Y} ∈ [0,734[.
                                                                                                             (17)
```

   The value k = 16 yields a range of validity [0,734[ containing [0,365[ – the range of the day of the year N_{Y} . Other values,
even smaller than 16, are possible, but an explanation for taking k = 16 will be given in Section 8.

### 7.1 Fast division–the special case a = 1, b = 0

This section examines n∕d with d > 0, that is, the particular case of the EAF f (n) = (a ⋅ n + b)∕d where a = 1, b = 0 and
d > 0.

***
  The supplementary material webpage^{25} contains C++ code that calculates the constants of Theorems 2 and 3, in addition to testing the validity of
some expressions presented in this paper (e.g., Examples 8 to 10).

   The next theorem appeared in Cavagnino and Werbrouck^{5} but it can also be obtained as a corollary of Theorem 2 for
a = 1 and b = 0. However, a more direct proof is presented in Section 14.2.

```
                                                                                         ⌈
                                                                                                ⌉
```

Theorem 4  (Fast division). Let d, k ∈ Z^{+} with d > 0. Set a^{′} = 2^{k} ∕d + 1, 𝜀 = d − 2^{k} %d and U = a^{′} ⋅ 𝜀^{−1} ⋅ d − 1. If 𝜀 ≤ a^{′},
then:

```
                                          n∕d  = a^{′} ⋅ n∕2^{k} ,

                                                            ∀n ∈ [0, U[.

                                                                                                                ▪
```

Proof. See Section 14.2.

Example 11 (Fast division by 1461). Consider the division n∕1461 that appears in Algorithm 1 (for n = N_{1}) and
Algorithm 3 (for n = N_{2}). For k = 32, the constants given by Theorem 4 are a^{′} = 2939745, 𝜀 = 149 and U = 28825529.
Since 𝜀 ≤ a^{′}, this theorem gives:

```
                                  n∕1461 = 2939745  ⋅ n∕2^{32},

                                                             ∀n ∈  [0, 28825529[.

                                                                                                             (18)
```

   The choice of k = 32 will be explained in Section 8.

### 7.2 Fast evaluation of residual functions

Theorems 2 and 3 provide optimisations for evaluating EAFs, generalising the current practice for divisions revisited by
Theorem 4. They can be used for an EAF f and for its minimal right inverse f^{∗}. The next result extends the optimisation
to residual functions and, in essence, states that if f^{′} matches f in a given interval (e.g., f^{′} might be a faster alternative for
f ), then f ◦^{′} also matches f ◦ in the same interval.

Theorem 5  (Alternative residual evaluation). Let f (n) = (a ⋅ n + b)∕d and f^{′}(n) = (a^{′} ⋅ n + b^{′})∕d^{′}, with d ≥ a > 0, and
assume that f (n) = f^{′}(n) for all n ∈ [L, U[. If f^{∗}(f (L)) = L and f^{′}(L − 1) < f^{′}(L), then:

```
                                           f ◦(n) = f ◦^{′}(n) ∀n ∈ [L, U[.
```

Proof. See Section 14.3.

```
                                                                                                                ▪
```

   In the case where f^{′}(n) = (a^{′} ⋅ n + b^{′})∕2^{k} , we have f ◦^{′}(n) = (a^{′} ⋅ n + b^{′})%2^{k} ∕a^{′}, so that the calculation of the remainder
is cheap. Although this is the most interesting application for us, the theorem also applies for other divisors. Hence, the
word Alternative instead of Fast in the theorem’s name.

Example 12 (Fast remainder of the division by 1461). Consider the EAFs f (n) = n∕1461, f^{∗}(q) = q ⋅ 1461 and f^{′}(n) =
2939745 ⋅ n∕2^{32}. Equation (18) shows that f (n) = f^{′}(n) for all n ∈ [0, 28825529[. Simple calculations give f^{∗}(f (0)) = 0 and
f^{′}(−1) = −1 < 0 = f^{′}(0). Hence, it follows from Theorem 5 that:

```
                             n%1461  = 2939745 ⋅ n%2^{32}∕2939745,

                                                                  ∀n ∈  [0, 28825529[.

                                                                                                             (19)
```

Example 13 (Fast calculation of day for the computational calendar). Example 5 shows that if the day of the year of X =
(Y , M, D) is N_{Y} , then D = m◦(N_{Y} ) = (5 ⋅ N_{Y} + 461)%153∕5 and m◦ is the residual function of m(N_{Y} ) = (5 ⋅ N_{Y} + 461)∕153
whose minimum right inverse is m^{∗}(M) = (153 ⋅ M − 457)∕5. In light of Equation (17), we wish to apply Theorem 5 to f =
m, f^{′}(N_{Y} ) = (2141 ⋅ N_{Y} + 197913)∕2^{16}, L = 0 and U = 734. For this, we need to verify that m^{∗}(m(0)) = 0 and that f^{′}(−1) <
f^{′}(0). Now, m(0) = (5 ⋅ 0 + 461)∕153 = 461∕153 = 3 and then, m^{∗}(m(0)) = m^{∗}(3) = (153 ⋅ 3 − 457)∕5 = (459 − 457)∕5 =
2∕5 = 0. We also have f^{′}(−1) = (−2141 + 197913)∕2^{16} = 195772∕65536 = 2 and f^{′}(0) = 197913∕2^{16} = 197913∕65536 = 3,
so that f^{′}(−1) < f^{′}(0). Theorem 5 therefore gives:

```
                      (5 ⋅ N_{Y} + 461)%153∕5 = (2141 ⋅ N_{Y} + 197913)%2^{16}∕2141,
```

8

```
                                                                              ∀N_{Y} ∈ [0,734[.

                                                                                                             (20)
```

## 8 Optimised algorithms for the Gregorian calendar

The remainder of our article focuses on algorithms for the Gregorian calendar. Of course, similar (and often simpler)
arguments apply to the Julian calendar. This section explains Algorithms 5 and 6, which are, respectively, optimised
versions of Algorithms 3 and 4.

Algorithm 5. Finds the Gregorian date (Y_{G}, M_{G}, D_{G}) from its rata dieN (The epoch is (0, 3, 1) of the Gregorian calendar)

     N_{1} = 4 ⋅ N + 3,
     C = N_{1}∕146097,
     N_{C} = N_{1}%146097∕4,

```
                               N_{2} = 4 ⋅ N_{C} + 3,
                               P_{2} = 2939745  ⋅ N_{2},
                               Z  = P_{2}∕2^{32},
                               N_{Y} = P_{2}%2^{32}∕2939745∕4,
                               Y  = 100 ⋅ C + Z,

                                                              N_{3} = 2141 ⋅ N_{Y} + 197913,
                                                              M =  N_{3}∕2^{16},
                                                              D =  N_{3}%2^{16}∕2141,

                                                                                            J   = 1{N_{Y} ≥306}
                                                                                            Y_{G} = Y + J,
                                                                                            M_{G} = M − 12 ⋅ J,
                                                                                            D_{G} = D + 1.
```

Algorithm 6. Calculates the rata die N of the Gregorian date (Y_{G}, M_{G}, D_{G}) (The epoch is (0, 3, 1) of the Gregorian
calendar)

```
                           Y =  Y_{G} − 1{M_{G}≤2},
                           M =  M_{G} + 12 ⋅ 1{M_{G}≤2},
                           D =  D_{G} − 1,
                           C =  Y ∕100,
```

FIGURE 5

```
                                                            y^{∗} = 1461 ⋅ Y ∕4 − C + C∕4,
                                                            m^{∗} = (979 ⋅ M − 2919)∕2^{5},
                                                            N  = y^{∗} + m^{∗} + D.
```

              Assembly emitted by Clang 15.0.0 (with -O3) for the addition to constants 2^{31} − 1 and 2^{31} + 1.

   The only difference between Algorithms 4 and 6 is the calculation of m^{∗}. The validity of the transformation was seen
in Example 9, and performance is improved by replacing division by 5 with division by 2^{5}.
   Similarly, Algorithm 5 is largely obtained from the results of Section 7 applied to the EAFs seen in Algorithm 3.
However, there is much more behind it than just using divisors that are powers of two. The sequel covers other aspects
related to modern superscalar CPUs and, more specifically, those of the x86_64 family. The presentation is split into
subsections, each of which compares a particular column of Algorithms 3 and 5.

### 8.1 First column

Algorithms 3 and 5 share the first column, which begs the question, Why do we not apply the results of Section 7 to find
faster alternatives for (4 ⋅ N + 3)∕146097 and (4 ⋅ N + 3)%146097∕4. Recall that such transformations are valid only on a
bounded interval, and since there are no limits on the rata die N this cannot be done. Well, this is the theory but, in practice,
applications set limits on the range of dates they support, which in turn set bounds on N. With this information in hand,
the results of Section 7 might be applied. Other aspects must nonetheless be considered: the exponent k in Theorems 2
and 3 must be large enough for U to surpass the requirement on N. The larger k becomes, the larger a^{′} is. If a^{′} gets too
large, then x86_64 cannot use a^{′} as an immediate value of arithmetic instructions and it must instead be loaded into a
register first, and there might be a price to pay for that. To illustrate this point, Figure 5 provides the assembly code for
the addition to two different constants. Note the extra mov required for the addition to the larger constant.
   Although the first column does not profit from the results of Section 7, it uses Theorem 1 for f ◦ = c◦ to obtain the
expression for N_{C} given by Equation (7). This allows for some interesting bit twiddling: the calculation of N_{C} requires that
of R = N_{1}%146097, from which N_{2} is obtained by N_{2} = 4 ⋅ (R∕4) + 3. It is easy to see that this expression is equivalent to
R | 3, where | denotes bitwise-or. Hence, the calculations of N_{C} and N_{2} can be replaced by:

```
                                     R =  N_{1}%146097

                                                         and

                                                                 N_{2} = R | 3.
```

In contrast, unaware of Equation (7), some implementations17,18 use the alternative N_{C} = N − c^{∗}(c(N)) = N − 146097 ⋅
C∕4, whose last operation is the subtraction and not the division by 4 necessary for this bit twiddling. Figure 6 contrasts

FIGURE 6

```
               Assembly emitted by Clang 15.0.0 (with -O3) for two alternatives up to the calculation of N_{2}
```

the assembly for the two alternatives. As we can see, the code on the left replaces two instructions seen on the right,
namely, shr (3 bytes) and lea (7 bytes), with a single or (3 bytes). The code on the left has fewer instructions and is
7 bytes shorter.

### 8.2 Second column

The input of the second column of Algorithms 3 and 5 is N_{C} = N_{1}%146097∕4 and we have 0 ≤ N_{C} ≤ 146096∕4 = 36,524.
Algorithm 3 evaluates Z = (4 ⋅ N_{C} + 3)∕1461 and N_{Y} = (4 ⋅ N_{C} + 3)%1461∕4, and the results of Section 7 can be used
to find faster alternatives valid for N_{C} ∈ [0, 36525[. However, doing so would forbid the bitwise-or trick explained
above as it relies on the expression 4 ⋅ N_{C} + 3. Hence, we keep the numerator N_{2} as in Algorithm 3 and seek to
optimise the calculations of the quotient Z = N_{2}∕1461 and remainder N_{Y} = N_{2}%1461. Now, since N_{C} ∈ [0, 36525[,
we have 0 ≤ N_{2} ≤ 4 ⋅ 36,524 + 3 = 146099. Therefore, N_{2} ∈ [0, 28825529[ and Equations (18) and (19) are used in
Algorithm 5.
   The second column of Algorithm 5 might look worse than that of Algorithm 3 but the opposite is true:^{†††} firstly, the
divisions by 2939745 and 4 can be collapsed into a single division by 2939745 ⋅ 4 = 11758980 and, secondly, following the
results of Granlund and Montgomery,^{6} compilers use the result of Theorem 4 for k = 54 and apply this strength reduction:

```
                             n∕11758980  = 1531969483  ⋅ n∕2^{54},

                                                                ∀n  ∈ [0, 10441974239[.
```

   Similarly, for Algorithm 3, compilers take k = 39 and Theorem 4 gives:

```
                            Z = N_{2}∕1461 = 376287347 ⋅ N_{2}∕2^{39},

                                                                 ∀N_{2} ∈ [0, 6958934390[,
```

and for the remainder, they use N_{2}%1461 = N_{2} − 1461 ⋅ (N_{2}∕1461). In summary, they calculate:

```
                            Z =  376287347 ⋅ N_{2}∕2^{39}

                                                        and

                                                                N_{Y} = (N_{2} − 1461 ⋅ Z)∕4.
```

   Note the dependency of N_{Y} on Z in the expressions above. This forces the CPU to wait for the calculation of Z to finish
before the calculation of N_{Y} can start. We emphasise that this derives from the dependency of the remainder N_{2}%1461
on the quotient N_{2}∕1461. In contrast, there is no dependency of P_{2}%2^{32} (which simply resets all but the 32 lower bits of
P_{2}) on P_{2}∕2^{32} or of N_{Y} on Z as calculated in Algorithm 5. Hence, once P_{2} is obtained, the evaluations of Z and N_{Y} can
start concurrently. Algorithm 5 can therefore benefit from the instruction-level parallelism of modern superscalar CPUs.

†††
  We rely on the transformations performed by some compilers. However, we can always manually enforce these transformations directly in the
source code, as we will explain further on.

There is a small but worthwhile price to pay for this, which, due to our choice of k = 32, can be avoided in x86_64 CPUs
as we will see now.
    The evaluations of Z and N_{Y} of Algorithm 5 need P_{2} to be stored in two different registers, which requires making
a copy (mov) of P_{2}. This does not necessarily increase the number of instructions. Indeed, for backward compatibility with older 32-bit CPUs, x86_64, the mov instruction might copy the lower 32-bits of one register into another
while resetting higher bits in the destination. Since this resetting is exactly what P_{2}%2^{32} does, this operation becomes
unnecessary.
    Figure 7 contrasts the assembly for the two alternative evaluations of Z and N_{Y} and presents a timeline analysis of
execution. Each line corresponds to an instruction and contains two important marks: the first e indicates the start of the
execution and E indicates its end. On the right, execution is sequential, with the start of any instruction only happening
at the end of the previous one. On the left, once the calculation of P_{2} (line 1) is finished, those of Z (line 3) and N_{Y} (lines
2, 4–5) begin. Consequently, the code on the left takes 10 cycles (line 0 shows cycle numbers modulus 10), whereas the
code on the right takes 12.

### 8.3 Third column

The third column of Algorithm 5 comes from Algorithm 3 and Equations (17) and (20). The assembly for the two alternatives and their respective timelines are shown in Figure 8. Most arguments seen above apply here, but we briefly touch
on a few points.

F I G U R E 7 Assembly emitted by Clang 15.0.0 (with -O3) for the two alternatives for Z and N_{Y} with their respective timelines
produced by llvm-mca (with -timeline -iterations=1 -march=x86-64 -mcpu=alderlake)

F I G U R E 8 Assembly emitted by Clang 15.0.0 (with -O3) for the two alternatives for M and D with their respective timeline produced
by llvm-mca (with -timeline -iterations=1 -march=x86-64 -mcpu=alderlake)

FIGURE 9

```
               Assembly emitted by Clang 15.0.0 (with -O3) for J with their respective timeline produced by llvm-mca (with -timeline
```

-iterations=1 -march=x86-64 -mcpu=alderlake)

    In Algorithm 3 (right of Figure 8), the division by 153 is strength-reduced and uses a multiplication by 3593175255.
This suffers from the issue illustrated by Figure 5: the multiplier is too large and cannot be an intermediate value operand
of imul, and must be first loaded into a register (line 3).^{‡‡‡}
    The execution depicted by the right timeline is essentially sequential, with almost all instructions starting only when
the previous one has finished. In contrast, the left timeline shows that the calculations of M (line 4) and D (lines 3, 5–6)
start concurrently.
    Finally, our choice of exponent 16 is also special for x86_64 CPUs. It allows the compiler to use movzx ecx, ax
(line 3), which copies 16 bits from ax to ecx and resets the upper 16-bits of the destination, rendering unnecessary the
operation %16.

### 8.4 Fourth column

Remark 1 presents two alternative expressions for J, namely, 1{M≥13} and 1_{{N}_{Y} ≥306}. They are used in Algorithms 3 and 5,
respectively. The second expression for J, 1_{{N}_{Y} ≥306}, has the advantage that N_{Y} is obtained earlier than M, allowing the
CPU to start J’s evaluation at earlier stages and while it performs other calculations in parallel.
    Figure 9 shows assembly and timelines for the relevant parts of Algorithm 5 (left) and Algorithm 3 (right). Both start
at the point just after N_{Y} is obtained.
    The code on the right offers a pleasant surprise: since M = N_{3}∕153, we deduce that M ≥ 13 if, and only if, N_{3} ≥
13 ⋅ 153 = 1989, which is the test seen in the assembly. This eliminates the dependency of J on M and the need for the
CPU to wait for the result of M to start the calculation of J. Still, in J’s evaluation (lines 6–8), the comparison with
1989 only starts after the calculation of N_{3} (lines 1–2) has finished. In contrast, for the code on the left, J’s evaluation
(lines 4–6) starts at the same time as N_{3}’s (lines 1–2). In total, the snippet on the left takes 8 cycles and that on the right
takes 9.

## 9 Changing the epoch

Our algorithms so far have set the epoch to X_{0} = (0, 3, 1), that is, 1 March 0000, and this section explains how they can
be adapted to another epoch X_{0}^{′} . Let K be the number of days from X_{0} to X_{0}^{′} , that is, K is the output of Algorithm 4^{§§§} for
X = X_{0}^{′} .

  Again, the compiler does not know that N_{Y} ∈ [0,365] so that N_{3} ∈ [461, 2286[, and pessimistically assumes that N_{3} can take any possible unsigned
32-bit value, implying the large multiplier.
§§§
  Performance is not a concern of this section and everything said about Algorithm 4 (resp., Algorithm 3) applies equally to Algorithm 6 (resp.,
Algorithm 5).

‡‡‡

    Let N^{′} be a given number of days from X_{0}^{′} to an unknown date X. The number of days from X_{0} to X is then N = K + N^{′}.
Hence, using the latter as an input of Algorithm 3 recovers X. Reciprocally, given a date X, Algorithm 4 yields the number
N of days from X_{0} to X. N^{′} = N − K is then the number of days from X_{0}^{′} to X.
    The case where K = 146097 ⋅ s, for some s ∈ Z, is quite interesting. To assess the impact of a shift by this amount, let N
be a given rata die, let N_{1}, C and N_{C} be as calculated by Algorithm 3 and let N_{1}^{′} , C^{′} and N_{C}^{′} be the corresponding quantities
for the shifted rata die N^{′} = N + 146097 ⋅ s. It is easy to see that:

```
                           N_{1}^{′} = N + 4 ⋅ 146097 ⋅ s,

                                                      C^{′} = C + 4 ⋅ s

                                                                       and

                                                                               N_{C}^{′} = N_{C} .
```

 N_{C} is hence invariant in this shift and so are all other quantities that only depend on N_{C} . In particular, M_{G} and D_{G} are
invariant. The century, however, shifts by 4 ⋅ s which means that Y and Y_{G} shift by 400 ⋅ s. In summary, shifting rata die
values by 146097 ⋅ s is equivalent to shifting years by 400 ⋅ s.

## 10 Choosing the right types

Performances of Algorithms 3 and 5 are deeply affected by types, since divisions of unsigned integer types are usually
faster than their signed counterparts. It is a common misconception that division by 2 is simply a bit shift to the right.
However, as Figure 10 shows, this does not hold for signed division in x86_64. Similar differences (extra shift and addition)
are seen for all divisors.
    It gets worse! For negative dividends, Euclidean division (which our algorithms rely on) does not match the integer
division prescribed by the C and C++ Standards. A workaround applied by some implementations11,18 is a conditional
adjustment similar to Figure 11. It is possible to avoid such adjustments and operate mainly on unsigned integers since
most of the quantities in our algorithms are non-negative numbers. For instance, in Algorithms 3 and 5, we have N_{C} =
R∕4, where R = N_{1}%146097, and since remainders are always non-negative, we get N_{C} ≥ 0. A quick inspection reveals
that the only quantities that might be negative are N, N_{1}, C, Y in Algorithms 3 and 5, and Y , C and y^{∗} in Algorithms 4
and 6. All other variables should have unsigned types.
    The epoch and application constraints also play a role in the choice of types. The basic forms of our algorithms use the
epoch 1 March 0000. If this is adequate and the application only supports dates on or after this epoch, then rata die values
are non-negative and a quick look at the algorithms shows that all the quantities they manipulate are positive numbers
and, thus, all variables should have unsigned types.
    Upper bounds are equally important for the choice of types. In Section 8.2, we found that N_{C} ≤ 36524, from which it
follows that N_{2} ≤ 146099. Similar arguments give bounds for all quantities of Algorithms 3 and 5 as summarised below:

            N_{C} ∈ [0, 36564],

```
                                              Z ∈  [0, 99],

                                                                       M  ∈ [3, 14],

                                                                                              M_{G} ∈ [1, 12],
```

            N_{2} ∈ [0, 146099],

```
                                            N_{Y} ∈ [0,365],

                                                                       D  ∈ [0, 30],

                                                                                              D_{G} ∈ [1, 31].
```

         P_{2} ∈ [0, 429493804755],

```
                                           N_{3} ∈ [0, 979378],

                                                                        J ∈ [0, 1],
```

 P_{2} therefore fits in 64-bits and all the other variables fit in 32-bit unsigned integers.

F I G U R E 10

```
                Assembly emitted by Clang 15.0.0 (with -O3) for unsigned (left) and signed (right) division by 2
```

F I G U R E 11

```
                Obtaining the Euclidean quotient from C/C++ integer division
```

   T A B L E 2 For each relevant date, N is its rata die (with respect to X_{0} = (0, 3, 1)) and N^{′} is the value of N shifted by 11979954.

    Date

```
                                                        N

                                                                                                     N ′
```

    1 January −32767

```
                                                        −11967960

                                                                                                     11994
```

    1 January 1970

```
                                                        719468

                                                                                                     12699422
```

    31 December 32767

```
                                                        11968205

                                                                                                     23948159
```

## 11 Algorithms for the Gregorian calendar with customised epochs

This section uses the results of the previous two to obtain a concrete, highly efficient C/C++ implementation of the
algorithms for the Gregorian calendar with a customised epoch.
   To be more concrete, we will consider the Unix epoch 1 January 1970, but the same arguments apply to other dates.
Similarly, we consider the requirements that the C++ Standard imposes on implementations: they must support all dates
from 1 January −32767 to 31 December 32767.
   The rata die with respect to X_{0} = (0, 3, 1) for each of the relevant dates (calculated by Algorithm 4) is presented in
Table 2. The range for which the implementation is required to be correct is thus [−11967960, 11968205]. This interval
contains negative numbers, which, in principle, requires us to evaluate Euclidean divisions on signed integers. As seen
in Section 10, this degrades performance. However, from Section 9, we know that the months and days obtained by
Algorithms 3 and 5 are invariant with respect to rata die shifts of the form 146097 ⋅ s, whereas years move by 400 ⋅ s.
Therefore, if s is large enough, the corresponding shift in rata die values will bring them to positive territory without
affecting months and days. Moreover, year moves can be corrected by subtracting L = 400 ⋅ s. This allows us to work on
unsigned integers throughout, except for the rata die shift and year correction, but these are additions and subtractions
and do not suffer from the performance issue that affects the division of signed types.
   For instance, for s = 82 we have −11967960 + 146097 ⋅ s = −11967960 + 11979954 = 11994 > 0. The right column of
Table 2 shows each value of N^{′} = N + 11979954. It also shows that moving the epoch to 1 January 1970 requires an extra
shift of 719468. Hence, the total shift is 719468 + 146097 ⋅ s, which amounts to K = 12699422 when s = 82.
   Shifts for s ≥ 82 also work. The choice of s obviously plays a role on the range of dates for which the implementation
produces correct results. This range is also bounded above by the possibility of overflow. Each unit increase in s moves
back the minimum and maximum supported dates by 400 years.^{¶¶¶}
   Putting these arguments together with Algorithm 5 yields the complete C++ implementation shown in Figure 12. We
refrained from applying some previously mentioned optimisations that certain compilers might figure out by themselves
(e.g., the bitwise-or trick), but implementers are encouraged to look at the assembly code generated by their compilers
and decide whether they need/want to manually perform these optimisations.
   Since Algorithm 6 is the inverse of Algorithm 5, their required shift and correction are in the opposite direction and
reversed order. Figure 13 shows a complete C++ implementation that applies this correction and shift to Algorithm 6.

## 12 Performance analysis

We benchmarked our implementations against counterparts in five of the most widely used C, C++, C#, and Java libraries,
as listed below:

glibc11,20
Boost^{17}
libc++^{18}
.NET^{12}
OpenJDK^{21}

```
                 The GNU  C Library.
                 The Boost C++  libraries.
                 LLVM’s implementation  of the C++ Standard Library.
                 Microsoft .NET framework.
                 Oracle’s open source implementation of the Java Platform SE (Android^{22} uses the same code).
```

   We used source files as publicly available on 2 May 2020. Non-C++ implementations have been ported to this language
and have all been slightly modified to achieve consistent (a) function signatures; (b) storage types (for year, month, day

¶¶¶
  Our implementation in GCC sets s = 3670 to move the middle of the interval of validity closer to the epoch. The implementation is valid in a range
far greater than the C++ Standard requirement.

  T A B L E 3 Relative CPU times for several platforms. The baseline (time = 1) is the code of Figure 12

   Platform

```
                                                          Fliegel-
                                                                                                       Reingold-
                                     .NET   Baum   Boost  Flandern  glibc Hatcher   libc++  OpenJDK    Dershowitz
```

   clang_11.0.0-linux-intel_i7_10510U

```
                                     4.23

                                            1.51

                                                   1.41

                                                          2.69

                                                                    8.14

                                                                          2.83

                                                                                    2.27

                                                                                            2.43

                                                                                                       7.51
```

   clang_11.0.0-linux-ryzen_7_1800X

```
                                     3.78

                                            1.57

                                                   1.43

                                                          2.79

                                                                    8.02

                                                                          2.90

                                                                                    2.40

                                                                                            2.68

                                                                                                       7.48
```

   clang_12.0.0-windows-ryzen_7_1800X 4.12

```
                                            2.01

                                                   1.41

                                                          2.99

                                                                    8.27

                                                                          2.78

                                                                                    2.47

                                                                                            2.35

                                                                                                       8.46
```

   clang_14.0.6-linux-intel_i7_10510U

```
                                     5.19

                                            2.20

                                                   1.60

                                                          3.22

                                                                    10.24 2.93

                                                                                    2.65

                                                                                            2.80

                                                                                                       9.56
```

   clang_14.0.6-linux-ryzen_7_1800

```
                                     4.26

                                            2.04

                                                   1.49

                                                          3.01

                                                                    8.47

                                                                          2.76

                                                                                    2.51

                                                                                            2.91

                                                                                                       8.80
```

   gcc_10.2.0-linux-intel_i7_10510U

```
                                     4.28

                                            1.54

                                                   1.31

                                                          2.41

                                                                    7.33

                                                                          2.45

                                                                                    2.20

                                                                                            2.18

                                                                                                       7.81
```

   gcc_10.2.0-linux-ryzen_7_1800X

```
                                     3.65

                                            1.50

                                                   1.23

                                                          2.37

                                                                    6.67

                                                                          2.31

                                                                                    2.23

                                                                                            2.12

                                                                                                       7.40
```

   gcc_12.1.0-linux-intel_i7_10510U

```
                                     4.24

                                            1.58

                                                   1.25

                                                          2.47

                                                                    7.67

                                                                          2.53

                                                                                    2.29

                                                                                            2.37

                                                                                                       7.99
```

   gcc_12.1.0-linux-ryzen_7_1800X

```
                                     3.65

                                            1.69

                                                   1.23

                                                          2.48

                                                                    7.08

                                                                          2.42

                                                                                    2.26

                                                                                            2.31

                                                                                                       7.67
```

   msvc_19.29-windows-ryzen_7_1800X

```
                                     3.85

                                            1.88

                                                   1.27

                                                          2.31

                                                                    7.12

                                                                          2.12

                                                                                    2.49

                                                                                            2.31

                                                                                                       7.81
```

   msvc_19.29-windows-intel_i7_8750H

```
                                     3.86

                                            1.83

                                                   1.46

                                                          2.44

                                                                    7.42

                                                                          2.19

                                                                                    2.62

                                                                                            2.37

                                                                                                       8.23
```

and rata die); and (c) epoch (1 January 1970). Some originals deal with date and time but our variants work on dates only.
(Given the uniform durations of days, hours, minutes and seconds, it would be trivial to incorporate the time component
to any dates-only algorithm.)
    We did not include Microsoft’s C++ Standard Library because back in May 2020 it did not yet implement these functionalities. Two other notable absences are libstdc++, the GNU implementation of the C++ Standard Library, and the
Linux Kernel because we have contributed our algorithms to those systems. They are available in libstdc++ from the
release of version 11 of the GNU Compiler Collection (GCC).^{26} The Linux Kernel27,28 features versions of Algorithm 5
from the release of version 5.14. Finally, the version of .NET that we used is outdated since they have recently replaced
most of their old implementation with our algorithm.
    We also considered our own implementations of algorithms described in the academic literature, namely Baum,^{13}
Fliegel and Flandern,^{14} Hatcher1,15,16 and Reingold and Dershovitz.^{19} The code of all implementations and the build
instructions are available on github^{25}
    Our time measurements were obtained with the help of the Google Benchmark library,^{29} to which we delegated the
task of producing statistically relevant results. The data shown in Figs. 14 and 15 were obtained on Windows 10 running
on Intel i7 8750H at 2.22 GHz. The code was compiled by MSVC version 19.29 at optimisation level /O2.
    The table in Figure 14 shows the time taken by each implementation to find the date for 16384 pseudo-random rata die
values, uniformly distributed in [−146097, 146097[ (i.e., Unix epoch ±400 years). They encompass the time spent scanning
the array of values (also shown). Subtracting the scanning time from that of each implementation gives a fairer account
of the time spent by the algorithm itself. The chart plots these adjusted timings relative to ours (code of Figure 12).
    Similarly, the table in Figure 15 shows the time taken by each algorithm to find rata die values corresponding to 16384
dates, uniformly distributed in [(1570, 1, 1), (2370, 1, 1)[ (again, Unix epoch ±400 years). The chart displays adjusted times
relative to ours (code of Figure 13).
    The Tables 3 and 4 show the values described above but for several platforms.

## 13 Proofs of results of Section 3

The remaining sections of this article present rigorous mathematical proofs for the results we have used to derive our
algorithms. This section covers the algebraic results of Section 3 and Section 14 sets out the numerical approximations
presented in Section 7.
    It is worth recalling the definition of Euclidean division: given integers n and d, with d ≠ 0, there exist unique integers
q and r such that 0 ≤ r < |d| and n = q ⋅ q + r. They are denoted q = n∕d and r = n%d and are respectively called the
quotient and the remainder of the division of n by d.
    Note the emphasis on the word unique above: there are many ways of decomposing n as n = d ⋅ q + r (e.g., for n = 17
and d = 5 we have 17 = 3 ⋅ 5 + 2 and 17 = 2 ⋅ 5 + 7) and it is therefore wrong to directly deduce from such a decomposition

F I G U R E  12

```
                   Function  that finds the proleptic Gregorian date that is N_{U} days from 1 January 1970. It was derived to give correct results
```

for, at least

F I G U R E  13

```
                   Function  that finds the number  of days from 1 January 1970 and  a given date of the proleptic Gregorian calendar. It was
```

derived to give correct results for, at least, all dates from 1 January −32767 to 31 December 32767 (although the range of validity is much larger)

   Reingold−Dershowitz

```
                         8.23
```

            OpenJDK

```
                         2.37

                           1
```

 Implementation

        Neri−Schneider

```
               libc++

                         2.62
```

             Hatcher

```
                         2.19

                glibc

                         7.42
```

       Fliegel−Flandern

```
                         2.44

               Boost

                         1.46

               Baum

                         1.83

               .NET

                         3.86

                                                                                             CPU

                                                                                                 8

                                                                                                 6

                                                                                                 4

                                                                                                 2

                       0

                                      2

                                                      4

                                                                     6

                                                                                     8

                                              Relative CPU Time
```

F I G U R E  14

```
                    Relative and absolute timings of date calculations
```

   Reingold−Dershowitz

```
                             3.96
```

            OpenJDK

```
                             2.88

                               1
```

 Implementation

        Neri−Schneider

```
               libc++

                             1.95
```

             Hatcher

```
                             1.73

                glibc

                             2.45
```

       Fliegel−Flandern

```
                             2.31

               Boost

                             1.95

               Baum

                             1.97

               .NET

                             2.37

                       0

                                                                                             CPU

                                                                                                 3

                                                                                                 2

                                                                                                 1

                                       1

                                                       2

                                                                       3

                                                                                       4

                                              Relative CPU Time
```

F I G U R E  15

```
                    Relative and absolute timings of rata die evaluations.
```

that q = n∕d and r = n%d. However, if in addition we prove that 0 < r < |d|, then the uniqueness allows this conclusion.
Therefore, in many of the following proofs, we will first decompose a given n as n = d ⋅ q + r and subsequently prove that
0 < r < |d| to conclude that q = n∕d and r = n%d.

Lemma 1  (Residual lemma). Let f (n) = (a ⋅ n + b)∕d, with d >  0, and g ∶ Z →  Z. For any q ∈ Z  such that f (g(q) − 1) <
f (g(q)), we have:

```
                                        n  ∈ Z    and

                                                          f (n) = f (g(q))

                                                                             ⇒

                                                                                    f ◦ (n) = n − g(q).
```

  T A B L E 4   Relative CPU times for several platforms. The baseline (time = 1) is the code of Figure 13

   Platform

```
                                                                    Fliegel
                                                                                                                         Reingold-
                                           .NET    Baum    Boost    -Flandern   glibc   Hatcher    libc++   OpenJDK      Dershowitz
```

   clang_11.0.0-linux-intel_i7_10510U

```
                                           2.29

                                                   1.65

                                                           1.89

                                                                    2.51

                                                                                2.55

                                                                                        2.58

                                                                                                   1.79

                                                                                                            3.16

                                                                                                                         3.56
```

   clang_11.0.0-linux-ryzen_7_1800X

```
                                           1.74

                                                   1.59

                                                           1.78

                                                                    2.25

                                                                                2.27

                                                                                        2.39

                                                                                                   1.77

                                                                                                            2.62

                                                                                                                         3.43
```

   clang_12.0.0-windows-ryzen_7_1800X 1.79

```
                                                   1.57

                                                           1.62

                                                                    2.39

                                                                                2.16

                                                                                        1.88

                                                                                                   1.88

                                                                                                            2.56

                                                                                                                         4.61
```

   clang_14.0.6-linux-intel_i7_10510U

```
                                           1.90

                                                   1.49

                                                           1.43

                                                                    2.27

                                                                                2.26

                                                                                        1.75

                                                                                                   1.60

                                                                                                            2.69

                                                                                                                         4.09
```

   clang_14.0.6-linux-ryzen_7_1800

```
                                           1.83

                                                   1.62

                                                           1.62

                                                                    2.41

                                                                                2.32

                                                                                        1.87

                                                                                                   1.83

                                                                                                            2.81

                                                                                                                         4.79
```

   gcc_10.2.0-linux-intel_i7_10510U

```
                                           2.40

                                                   1.48

                                                           1.76

                                                                    2.34

                                                                                2.85

                                                                                        2.28

                                                                                                   1.67

                                                                                                            3.09

                                                                                                                         3.39
```

   gcc_10.2.0-linux-ryzen_7_1800X

```
                                           1.97

                                                   1.55

                                                           1.71

                                                                    2.13

                                                                                2.82

                                                                                        2.15

                                                                                                   1.65

                                                                                                            2.77

                                                                                                                         3.22
```

   gcc_12.1.0-linux-intel_i7_10510U

```
                                           2.36

                                                   1.57

                                                           1.50

                                                                    2.43

                                                                                3.02

                                                                                        2.04

                                                                                                   1.66

                                                                                                            3.35

                                                                                                                         4.19
```

   gcc_12.1.0-linux-ryzen_7_1800X

```
                                           1.87

                                                   1.47

                                                           1.52

                                                                    2.19

                                                                                2.77

                                                                                        1.99

                                                                                                   1.60

                                                                                                            2.81

                                                                                                                         3.90
```

   msvc_19.29-windows-ryzen_7_1800X

```
                                           2.65

                                                   2.20

                                                           2.17

                                                                    2.40

                                                                                2.65

                                                                                        1.93

                                                                                                   2.07

                                                                                                            3.05

                                                                                                                         4.52
```

   msvc_19.29-windows-intel_i7_8750H

```
                                           2.37

                                                   1.97

                                                           1.95

                                                                    2.31

                                                                                2.45

                                                                                        1.73

                                                                                                   1.95

                                                                                                            2.88

                                                                                                                         3.96
```

Proof. For any x ∈ Z  we have x%d =  x − d ⋅ (x∕d), which applied to arbitrary x_{1} and x_{2} yields:

```
                                        [
                                                         ]   [
                                                                              ]
                      x_{1} %d − x_{2} %d = x_{1} − d ⋅ (x_{1} ∕d) − x_{2} − d ⋅ (x_{2} ∕d) = (x_{1} − x_{2} ) − d ⋅ (x_{1} ∕d − x_{2} ∕d).
```

In particular, for x_{1} = a ⋅ n + b and x_{2} = a ⋅ g(q) + b we obtain:

```
                                                                   [
                                                                                                       ]
```

        (a ⋅ n + b)%d −  (a ⋅ g(q) + b)%d =  a ⋅ (n − g(q)) − d ⋅ (a ⋅ n + b)∕d −  (a ⋅ g(q) + b)∕d

```
                                                                   [
                                                                                    ]
                                            =  a ⋅ (n − g(q)) − d ⋅ f (n) − f (g(q))

                                            =  a ⋅ (n − g(q))
```

   ∴

```
                                                                                                              (by definition  of f )

                                                                                                           (from  f (n) = f (g(q)).)

                            (a ⋅ n + b)%d   =  a ⋅ (n − g(q)) + (a ⋅ g(q) + b)%d
```

Provided we show that 0 ≤ (a ⋅ g(q) + b)%d < a it will follow that (a ⋅ n + b)%d∕a =  n − g(q), that is, f ◦ (n) = n − g(q).
    Trivially, 0 ≤ (a ⋅ g(q) + b)%d as it is a result of %. Now, suppose by contradiction that (a ⋅ g(q) + b)%d ≥  a. Then,

           a ⋅ (g(q) − 1) + b = (a ⋅ g(q) + b) − a

```
                                    [
                                                      ]
                              =  d ⋅ (a ⋅ g(q) + b)∕d   + (a ⋅ g(q) + b)%d  −  a

                              =  d ⋅ f (g(q)) + (a ⋅ g(q) + b)%d − a

                              ≥  d ⋅ f (g(q))
```

  ∴ (a ⋅ (g(q) − 1) + b)∕d ≥  f (g(q))

  ∴

```
                      f (g(q)) ≥ f (g(q) − 1)
```

The above contradicts the assumption on q.

```
                                                                                        (by  Euclidean   division  (a ⋅ g(q) + b)∕d)

                                                                                                               (by  definition  of f )

                                                                                   (from  the  assumption    (a ⋅ g(q) + b)%d  ≥  a.)

                                                                                                    (by dividing  both  sides by  d.)

                                                                                                               (by definition  of f .)

                                                                                                                                        ▪
```

    The following is a more complete form of Theorem 1.

Theorem 6   (EAF Theorem complete form). Let f (n) = (a ⋅ n + b)∕d with d ≥ a > 0. Then, for any n ∈ Z  and q ∈ Z we
have:

  1. f (f^{∗} (q)) = q and f (f^{∗} (q) − 1) = q − 1;
  2. f (n) = q if, and only if , n ∈ [f^{∗} (q), f^{∗} (q + 1)[;
  3. n ∈ [f^{∗} (f (n)), f^{∗} (f (n) + 1)[ and f ◦ (n) = n − f^{∗} (f (n)).

Proof. (1) Since 0 ≤ (a^{∗} ⋅ q + b^{∗})%a ≤ a − 1 and a ≤ d, we have:

```
                               0 ≤ a − 1 − (a^{∗} ⋅ q + b^{∗})%a ≤ d − 1 − (a^{∗} ⋅ q + b^{∗})%a < d.

                                                                                                                  (21)
```

We also have:

```
                                 [
                                                 ]
               a ⋅ f^{∗}(q) + b = a ⋅ (a^{∗} ⋅ q + b^{∗})∕d^{∗}) + b
                                 [
                                                ]
                           =  a ⋅ (a^{∗} ⋅ q + b^{∗})∕a) + b

                                                                                 (by definition of f^{∗})

                                                                                       (from d^{∗} = a)

                                                                 (from  a ⋅ (x∕a) = x − x%a, ∀x ∈ Z)

                           =  a^{∗} ⋅ q + b^{∗} − (a^{∗} ⋅ q + b^{∗})%a + b
                                     [
                                                            ]
                           =  d ⋅ q + a − 1 − (a^{∗} ⋅ q + b^{∗})%a
```

     ∴

```
                                                                   (from a^{∗} = d and b^{∗} = a − b − 1.)
```

           (a ⋅ f^{∗}(q) + b)∕d = q

```
                                                                                     (from Eq. (21))

                      ∗

                                                                                  (by definition of f )

                   f (f (q)) = q
```

     ∴

```
                                                                                                                  (22)
```

This is the first conclusion of Item 1. Now, subtracting a from both sides of Equation (32) gives:

          a ⋅ (f^{∗}(q) − 1) + b = d ⋅ q − 1 − (a^{∗} ⋅ q + b^{∗})%a

```
                                                                 ]
                                          [
                           =  d ⋅ (q − 1) + d − 1 − (a^{∗} ⋅ q + b^{∗})%a (by subtracting and adding d to the right side.)
```

  ∴ (a ⋅ (f^{∗}(q) − 1) + b)∕d = q − 1

  ∴

```
                                                                                                    (from Eq. (21))

                  ∗

                f (f (q) − 1) = q − 1

                                                                                                (by definition of f )
```

This concludes the proof of Item 1.
    (2) Since d > 0 and a > 0, f (n) = (a ⋅ n + b)∕d is non-decreasing and, thus, the following holds:

         n ≤ f^{∗}(q) − 1

```
                         ⇒

                              f (n) ≤ f (f^{∗}(q) − 1) = q − 1
```

             f^{∗}(q) ≤ n

```
                         ⇒

                              q = f (f^{∗}(q)) ≤ f (n)
```

     n ≤ f^{∗}(q + 1) − 1

```
                         ⇒

                              f (n) ≤ f (f^{∗}(q + 1) − 1) = q
```

         f^{∗}(q + 1) ≤ n

```
                        ⇒

                              q + 1f (f^{∗}(q + 1)) ≤ f (n)

                                                                             (the equality comes from Item 1)

                                                                                                                  (23)

                                                                                                        (ditto)

                                                                                                                  (24)

                                                             (the equality comes from Item 1 applied to q + 1)

                                                                                                                  (25)

                                                                                                       (ditto.)

                                                                                                                  (26)
```

    Equations (23) and (26) show that if n ∉ [f^{∗}(q), f^{∗}(q + 1)[, then f (n) ≠ q whereas Eqs. (24) and (25) show that if n ∈
[f (q), f^{∗}(q + 1)[, then f (n) = q. This concludes Item 2.
    (3) Since d ≥ a > 0, we have a^{∗} ≥ d^{∗} > 0 and f^{∗}(q) = (a^{∗} ⋅ q + b^{∗})∕d^{∗} is strictly increasing. Then for q large enough,
we have n < f^{∗}(q + 1) and we pick the minimum such q. From the minimality of q we obtain f^{∗}(q) ≤ n, that is, n ∈
[f^{∗}(q), f^{∗}(q + 1)[. Hence, Item 2 yields f (n) = q and therefore, n ∈ [f^{∗}(f (n)), f^{∗}(f (n) + 1)[.
    We will show that f^{∗} fulfills the hypotheses on g of Lemma 1 and then conclude that f ◦(n) = n − f^{∗}(q) = n − f^{∗}(f (n)).
Item 1 gives f (f^{∗}(q) − 1) = q − 1 < q = f (f^{∗}(q)) and from f (n) = q and Item 1 again, we obtain f (n) = f (f^{∗}(q)).

```
                                                                                                                     ▪
```

  ∗

14

## 14 Proofs of results of Section 7

            Fast evaluation of Euclidean affine functions

For ease of reference, we restate Theorems 2 and 3 before giving their proofs.

Theorem 2  (Fast round-up EAF evaluation). Let k ∈ Z^{+} and f (n) = (a ⋅ n + b)∕d with d > 0. Set a^{′} = 2^{k} ⋅ a∕d + 1, b^{′} =
       {

```
                                   }
```

− min a^{′} ⋅ n − 2^{k} ⋅ f (n) ; n ∈ [0, d[ and 𝜀 = d − 2^{k} ⋅ a%d. For n ∈ [0, d[ define:

```
                            {
                                                                         }
                Q(n)  = min   q ∈ Z^{+} ; 𝜀 ⋅ q ≥ 2^{k} ⋅ (1 + f (n)) − (a^{′} ⋅ n + b^{′})

                                                                             and

                                                                                   P(n) = d ⋅ Q(n) + n.
```

 Let U = min{P(n) ; n ∈ [0, d[ }. Then,

```
                                                            )
                                                  (
                                   (a ⋅ n + b)∕d = a^{′} ⋅ n + b^{′} ∕2^{k} ,

                                                                    ∀n ∈  [0, U[.
```

Proof. Let n ∈ Z and note that 𝜀 > 0. Hence, if q is large enough, then 𝜀 ⋅ q becomes greater than 2^{k} ⋅ (1 + f (n)) − (a^{′} ⋅
n + b^{′}). Therefore, Q(n) is well-defined and so are P(n) and U. Furthermore, Q(n) ≥ 0 and, since d > 0, we also have
P(n) ≥ 0 and, consequently, U ≥ 0. We do not exclude the possibility that U = 0, in which case this theorem’s conclusion
is vacuously true. The sequel assumes that U > 0 and n ∈ [0, U[. We have:

 a^{′} ⋅ n + b^{′} = a^{′} ⋅ {d ⋅ (n∕d) + n%d} + b^{′}
           {

```
                       }
```

         = a^{′} ⋅ d − 2^{k} ⋅ a ⋅ (n∕d) + 2^{k} ⋅ a ⋅ (n∕d) + a^{′} ⋅ (n%d) + b^{′}

```
                       ]
                                 }
```

           {[
         = 1 + 2^{k} ⋅ a∕d ⋅ d − 2^{k} ⋅ a ⋅ (n∕d) + 2^{k} ⋅ a ⋅ (n∕d) + a^{′} ⋅ (n%d) + b^{′}
           {

```
                [
                                 ]}
```

         = d − 2^{k} ⋅ a − (2^{k} ⋅ a∕d) ⋅ d ⋅ (n∕d) + 2^{k} ⋅ a ⋅ (n∕d) + a^{′} ⋅ (n%d) + b^{′}
           {

```
                       }
```

         = d − 2^{k} ⋅ a%d ⋅ (n∕d) + 2^{k} ⋅ a ⋅ (n∕d) + a^{′} ⋅ (n%d) + b^{′}

```
                                                                                                (by division of nby d)

                                                                                (by subtracting and adding 2^{k} ⋅ a ⋅ (n∕d))

                                                                                                  (by definition of a^{′})

                                                                                            (by division of 2^{k} ⋅ a by d))
```

         = 𝜀 ⋅ (n∕d) + 2^{k} ⋅ a ⋅ (n∕d) + a^{′} ⋅ (n%d) + b^{′}

```
                                                                                                  (by definition of 𝜀.)
```

         = 𝜀 ⋅ (n∕d) + 2 ⋅ {a ⋅ (n∕d) + f (n%d)} − 2 ⋅ f (n%d) + a ⋅ (n%d) + b

```
                        {
                                  [
                                              ]  }
```

         = 𝜀 ⋅ (n∕d) + 2^{k} ⋅ a ⋅ (n∕d) + a ⋅ (n%d) + b ∕d − 2^{k} ⋅ f (n%d) + a^{′} ⋅ (n%d) + b^{′}

```
                        {[
                                                ]  }
```

         = 𝜀 ⋅ (n∕d) + 2^{k} ⋅ a ⋅ d ⋅ (n∕d) + a ⋅ (n%d) + b ∕d − 2^{k} ⋅ f (n%d) + a^{′} ⋅ (n%d) + b^{′}

```
                        {[
                                               ]  }
```

         = 𝜀 ⋅ (n∕d) + 2^{k} ⋅ a ⋅ (d ⋅ (n∕d) + (n%d)) + b ∕d − 2^{k} ⋅ f (n%d) + a^{′} ⋅ (n%d) + b^{′}

```
                     k

                                            k

                                                       ′

                                                                                  (by adding and subtracting 2^{k} ⋅ f (n%d))

                                                                  ′

                                                                                              (by definition of f (n%d))

                                                                                     (since a ⋅ d ⋅ (n∕d) is multiple of d)
```

         = 𝜀 ⋅ (n∕d) + 2^{k} ⋅ {(a ⋅ n + b)∕d} − 2^{k} ⋅ f (n%d) + a^{′} ⋅ (n%d) + b^{′}

```
                             [
                                                              ]
```

         = 2^{k} ⋅ {(a ⋅ n + b)∕d} + 𝜀 ⋅ (n∕d) + a^{′} ⋅ (n%d) + b^{′} − 2^{k} ⋅ f (n%d) .

```
                                                                                                (by division of n by d)

                                                                                                                (27)
```

 Set r = 𝜀 ⋅ (n∕d) + a^{′} ⋅ (n%d) + b^{′} − 2^{k} ⋅ f (n%d), the term inside the square brackets of the last equation, so that a^{′} ⋅ n +
b^{′} = 2^{k} ⋅ {(a ⋅ n + b)∕d} + r. We shall show that 0 ≤ r < 2^{k} and it will follow that (a^{′} ⋅ n + b^{′})∕2^{k} = (a ⋅ n + b)∕d, which
concludes the proof.
   Since n%d ∈ [0, d[, the definition of b^{′} yields −b ≤ a^{′} ⋅ (n%d) − 2^{k} ⋅ f (n%d), or equivalently, 0 ≤ a^{′} ⋅ (n%d) + b^{′} − 2^{k} ⋅
f (n%d). This and 𝜀 ⋅ (n∕d) ≥ 0 give r ≥ 0. Now, by definition, U ≤ P(n%d) and thus, n < P(n%d). From n = d ⋅ (n∕d) +
n%d and P(n%d) = d ⋅ Q(n%d) + n%d we obtain d ⋅ (n∕d) + n%d < d ⋅ Q(n%d) + n%d, so that n∕d < Q(n%d). From the
minimality of Q(n%d) and the fact that 0 ≤ n∕d < Q(n%d), we obtain:

```
                                     𝜀 ⋅ (n∕d) < 2^{k} ⋅ (1 + f (n%d)) − (a^{′} ⋅ (n%d) + b^{′})

                                  ∴

                                     𝜀 ⋅ (n∕d) + a^{′} ⋅ (n%d) + b^{′} − 2^{k} ⋅ f (n%d) < 2^{k} .

                                                                                                                  ▪
```

And it follows that r < 2^{k} .

Theorem 3  (Fast round-down EAF evaluation). Let k ∈ Z^{+} and f (n) = (a ⋅ n + b)∕d with d > 0 and 2^{k} ⋅ a%d > 0. Set

```
                          {
                                                              }
```

a^{′} = 2^{k} ⋅ a∕d and b^{′} = min 2^{k} − 1 − a^{′} ⋅ n + 2^{k} ⋅ f (n) ; n ∈ [0, d[ and 𝜀 = 2^{k} ⋅ a%d. For n ∈ [0, d[ define:

```
                              {
                                                                    }
                  Q(n) =  min  q ∈ Z^{+} ; 𝜀 ⋅ q > (a^{′} ⋅ n + b^{′}) − 2^{k} ⋅ f (n)

                                                                        and

                                                                              P(n) = d ⋅ Q(n) + n.
```

 Let U = min{P(n) ; n ∈ [0, d[ }. Then,

```
                                    (a ⋅ n + b)∕d = (a^{′} ⋅ n + b^{′})∕2^{k} ,

                                                                    ∀n ∈ [0, U[.
```

Proof. Let n ∈ Z. Since 𝜀 > 0, if q is large enough, then 𝜀 ⋅ q becomes greater than (a^{′} ⋅ n + b^{′}) − 2^{k} ⋅ f (n). Therefore, Q(n)
is well-defined and so are P(n) and U. Furthermore, Q(n) ≥ 0 and, since d > 0, we also have P(n) ≥ 0 and, consequently,
U ≥ 0. We do not exclude the possibility that U = 0, in which case this theorem’s conclusion is vacuously true. The sequel
assumes that U > 0 and n ∈ [0, U[. We have:

              [

```
                            ]
```

a^{′} ⋅ n + b^{′} = a^{′} ⋅ d ⋅ (n∕d) + n%d + b^{′}
           [

```
                      ]
```

         = a^{′} ⋅ d − 2^{k} ⋅ a ⋅ (n∕d) + 2^{k} ⋅ a ⋅ (n∕d) + a^{′} ⋅ (n%d) + b^{′}
           [(

```
                   )
                             ]
```

         = 2^{k} ⋅ a∕d ⋅ d − 2^{k} ⋅ a ⋅ (n∕d) + 2^{k} ⋅ a ⋅ (n∕d) + a^{′} ⋅ (n%d) + b^{′}
           [ _{k}

```
                    ]
```

         = −2 ⋅ a%d ⋅ (n∕d) + 2^{k} ⋅ a ⋅ (n∕d) + a^{′} ⋅ (n%d) + b^{′}

```
                                                                                                 (by division of n by d)

                                                                                  (by subtracting and adding 2^{k} ⋅ a ⋅ (n∕d))

                                                                                                   (by definition of a^{′})

                                                                                             (by division of 2^{k} ⋅ a by d))
```

         = −𝜀 ⋅ (n∕d) + 2^{k} ⋅ a ⋅ (n∕d) + a^{′} ⋅ (n%d) + b^{′}

```
                                                                                                    (by definition of 𝜀.)
```

         = −𝜀 ⋅ (n∕d) + 2 ⋅ {a ⋅ (n∕d) + f (n%d)} − 2 ⋅ f (n%d) + a ⋅ (n%d) + b

```
                         {
                                   [
                                               ]  }
```

         = −𝜀 ⋅ (n∕d) + 2^{k} ⋅ a ⋅ (n∕d) + a ⋅ (n%d) + b ∕d − 2^{k} ⋅ f (n%d) + a^{′} ⋅ (n%d) + b^{′}

```
                         {[
                                                 ]  }
```

         = −𝜀 ⋅ (n∕d) + 2^{k} ⋅ a ⋅ d ⋅ (n∕d) + a ⋅ (n%d) + b ∕d − 2^{k} ⋅ f (n%d) + a^{′} ⋅ (n%d) + b^{′}

```
                         {[
                                                ]  }
```

         = −𝜀 ⋅ (n∕d) + 2^{k} ⋅ a ⋅ (d ⋅ (n∕d) + (n%d)) + b ∕d − 2^{k} ⋅ f (n%d) + a^{′} ⋅ (n%d) + b^{′}

```
                      k

                                             k

                                                         ′

                                                                   ′

                                                                                   (by adding and subtracting 2^{k} ⋅ f (n%d))

                                                                                               (by definition of f (n%d))

                                                                                       (since a ⋅ d ⋅ (n∕d) is multiple of d)
```

         = −𝜀 ⋅ (n∕d) + 2^{k} ⋅ {(a ⋅ n + b)∕d} − 2^{k} ⋅ f (n%d) + a^{′} ⋅ (n%d) + b^{′}

```
                            [
                                                               ]
```

         = 2^{k} ⋅ {(a ⋅ n + b)∕d} + −𝜀 ⋅ (n∕d) + a^{′} ⋅ (n%d) + b^{′} − 2^{k} ⋅ f (n%d) .

```
                                                                                                 (by division of n by d)
```

   Set r = −𝜀 ⋅ (n∕d) + a^{′} ⋅ (n%d) + b^{′} − 2^{k} ⋅ f (n%d), the term inside the square brackets of the last equation, so that a^{′} ⋅
n + b^{′} = 2^{k} ⋅ {(a ⋅ n + b)∕d} + r. We shall show that 0 ≤ r < 2^{k} and it will follow that (a^{′} ⋅ n + b^{′})∕2^{k} = (a ⋅ n + b)∕d, which
concludes the proof.
   Since n%d ∈ [0, d[, the definition of b^{′} yields b^{′} ≤ 2^{k} − 1 − a^{′} ⋅ (n%d) + 2^{k} ⋅ f (n%d), or equivalently, a^{′} ⋅ (n%d) + b^{′} −
2^{k} ⋅ f (n%d) ≤ 2^{k} − 1. This and 𝜀 ⋅ (n∕d) ≥ 0 give r ≤ 2^{k} − 1. Now, by definition, U ≤ P(n%d) and thus, n < P(n%d). From
n = d ⋅ (n∕d) + n%d and P(n%d) = d ⋅ Q(n%d) + n%d we get d ⋅ (n∕d) + n%d < d ⋅ Q(n%d) + n%d, so that n∕d < Q(n%d).
From the minimality of Q(n%d) and the fact that 0 ≤ n∕d < Q(n%d), we obtain:

```
                                      𝜀 ⋅ (n∕d) ≤ (a^{′} ⋅ (n%d) + b^{′}) − 2^{k} ⋅ f (n%d)

                                  ∴

                                      0 ≤ −𝜀 ⋅ (n∕d) + a^{′} ⋅ (n%d) + b^{′} − 2^{k} ⋅ f (n%d).
```

And it follows that r ≥ 0.

```
                                                                                                                   ▪
```

   Recall that by applying Theorems 2 and 3 in Examples 8 and 9 we obtained two fast alternatives for the same EAF
valid on [0, 12[ and [0, 34[, respectively. Hence, the larger interval was obtained with Theorem 3 but sometimes it is
Theorem 3 that yields the larger interval. For EAFs known prior to compilation, we can find both alternatives and select
the one that is valid in the larger range. A simple criterion for guessing in advance the theorem that will give the larger
interval is based on the following heuristics. To maximise U we seek to maximise Q(N), which is the minimum value of
q for which 𝜀 ⋅ q reaches a certain threshold. The smaller 𝜀 is, the larger q must be for this to happen. Hence, it is likely
that a larger interval will be obtained for the alternative with the smallest 𝜀 amongst its two possible values, namely,
𝜀_{1} = d − 2^{k} ⋅ a%d and 𝜀_{2} = 2^{k} ⋅ a%d. (If d is odd, then 𝜀_{1} ≠ 𝜀_{2}.) Another interpretation of this criterion is that it sets a^{′} to

```
                                            ⌈
                                                       ⌉   ⌊
                                                                      ⌋
```

the best approximation of 2^{k} ⋅ a ⋅ d^{−1} given by 2^{k} ⋅ a ⋅ d^{−1} or 2^{k} ⋅ a ⋅ d^{−1} . Indeed, suppose that 2^{k} ⋅ a ⋅ d^{−1} is not integer,
       ⌈ _{k}

```
                  ⌉
                                      ⌊
                                                 ⌋
```

so that 2 ⋅ a ⋅ d^{−1} = 2^{k} ⋅ a∕d + 1 and 2^{k} ⋅ a ⋅ d^{−1} = 2^{k} ⋅ a∕d. Then:

```
                    𝜀_{1} = d − 2^{k} ⋅ a%d,
                                                  ]
                              [
                        = d −  2^{k} ⋅ a − (2^{k} ⋅ a∕d) ⋅ d ,

                        = (1 + 2^{k} ⋅ a∕d) ⋅ d − 2^{k} ⋅ a,
                                     ⌉
                          ⌈
                        =  2^{k} ⋅ a ⋅ d^{−1} ⋅ d − 2^{k} ⋅ a,

                                                                  𝜀_{2} = 2^{k} ⋅ a%d,

                                                                     = 2^{k} ⋅ a − (2^{k} ⋅ a∕d) ⋅ d,

                                                                                          ⌋
                                                                               ⌊
                                                                     = 2^{k} ⋅ a − 2^{k} ⋅ a ⋅ d^{−1} ⋅ d.

                                   ⌈
                                              ⌉
                                                                           ⌊
                                                                                      ⌋
```

It follows that 𝜀_{1} < 𝜀_{2} if, and only if, 2^{k} ⋅ a ⋅ d^{−1} − 2^{k} ⋅ a ⋅ d^{−1} < 2^{k} ⋅ a ⋅ d^{−1} − 2^{k} ⋅ a ⋅ d^{−1} .

### 14.2 Fast division – the special case a = 1, b = 0

We again turn our attention to division n∕d with d > 0, that is, the particular case of the EAF f (n) = (a ⋅ n + b)∕d where
a = 1, b = 0 and d > 0 and d is not a power of two.

```
                                      ⌈
                                              ⌉
                                                    ⌊
                                                            ⌋
```

   For a = 1, Theorems 2 and 3 set a^{′} to 2^{k} ⋅ d^{−1} and 2^{k} ⋅ d^{−1} , respectively. The former is the choice made by Alverson^{4}
Cavagnino and Werbrouck,^{5} and Granlund and Montgomery,^{6} while the latter is used by Magenheimer.^{7} Finally, Robison^{8}

and an appendix to Cavagnino and Werbrouck^{5} consider both, and in this particular case, rigourously justify the heuristics
we have suggested for choosing between the two approaches.
   This section follows a more direct path but most of its results can be obtained from Theorems 2 and 3 for a = 1 and
b = 0. For instance, for a^{′} > 0, let b^{′} = − min{a^{′} ⋅ n − 2^{k} ⋅ f (n) ; n ∈ [0, d[ } as in Theorem 2. Since f (n) = n∕d = 0, for
n ∈ [0, d[, we have b^{′} = − min{a^{′} ⋅ n ; n ∈ [0, d[ } = 0. Hence, in contrast to the general case, there is no need for an O(d)
search to obtain b^{′}. Similarly, b^{′} = min{2^{k} − 1 − a^{′} ⋅ n + 2^{k} ⋅ f (n) ; n ∈ [0, d[ }, as defined by Theorem 3, can be proven to
be a^{′} + 2^{k} %d − 1, the same value found in Magenheimer et al.^{7} Theorem 3 assumes that 2^{k} %d ≥ 1 and, in the definition
of b^{′}, had we subtracted 2^{k} %d instead of 1, the proof would still work but b^{′} would have a smaller value, namely, b^{′} = a^{′}.
In this case, the final reduction would be n∕d = a^{′} ⋅ (n + 1)∕2^{k} as found in Cavagnino and Werbrouck^{5} and in Robison.^{8}
In addition, by making b^{′} smaller, Q(n), P(n) and U can also decrease. Hence, Theorem 3 obtains a range of validity that
is no smaller than the one obtained in in Cavagnino and Werbrouck^{5} and in Robison.^{8}
   The remainder of this section focuses on the round-up approach, but the round-down alternative could be similarly
considered. Our reasons for this are that the round-up approach is mostly used by compilers and that the appearance of
the term n + 1 can lead to an overflow if it is not dealt with appropriately.

Lemma 2. Let d, k ∈ Z^{+} with d > 0. Set a^{′} = 2^{k} ∕d + 1 and 𝜀 = d − 2^{k} %d. Then, a^{′} ⋅ d = 2^{k} + 𝜀 and for any n ∈ Z we have:

```
                          a^{′} ⋅ n∕2^{k} = n∕d if, and only if,

                                                          0 ≤ 𝜀 ⋅ (n∕d) + a^{′} ⋅ (n%d) < 2^{k} .

                                                                                                             (28)
```

Proof. Taking a = 1 and b = 0 in Theorem 2 gives the same a^{′} and 𝜀 as here. Now,

              a^{′} ⋅ d = (2^{k} ∕d + 1) ⋅ d

```
                                                     (by definition of a^{′})

                   = (2^{k} ∕d) ⋅ d + d

                   = 2^{k} − 2^{k} ∕%d + d

                                                 (by division of 2^{k} by d)

                   = 2^{k} + 𝜀

                                                     (by definition of 𝜀.)
```

   Similarly, we have b^{′} = 0 and f (n%d) = n%d∕d = 0. From Equation (27) with f (n%d) = n%d∕d = 0 we obtain:

```
                                     a^{′} ⋅ n − 2^{k} ⋅ (n∕d) = 𝜀 ⋅ (n∕d) + a^{′} ⋅ (n%d).
```

The result follows from Euclidean division by 2^{k} .

```
                                                                                                               ▪
```

   Since a^{′}, 𝜀 ∈ Z^{+}, we have 0 ≤ 𝜀 ⋅ (n∕d) + a^{′} ⋅ (n%d) for all n ∈ Z^{+}. There is an illuminating geometric interpretation
for the second part of the double inequality in Equation (28) that follows from mapping each n ∈ Z^{+} to P_{n} = (n∕d, n%d) in
the xy plane. Figure 16 shows, for d = 5, some points of particular interest, namely, P_{0}, P_{1}, P_{3}, P_{4}, P_{5}, and P_{64}, respectively

```
                                                                                       k
                                                                           ′
                               0 ,
                                  1 ,
                                     3 ,
                                        4 ,
                                            5 , and 64 . Inequality 𝜀 ⋅ (n∕d) + a ⋅ (n%d) < 2 states that P_{n} is below the
```

pictured by their circled values:

```
                   k
```

            ′
line 𝜀 ⋅ x + a ⋅ y = 2 , which is represented by the dotted line in Figure 16, for k = 8, 𝜀 = 4 and a^{′} = 52. Hence, Equation
(28) means that a point below the line corresponds to n ∈ Z^{+} such that n∕d = a^{′} ⋅ n∕2^{k} and a point above or on the line
is related to n such that n∕d ≠ a^{′} ⋅ n∕2^{k} .
   If the slope of the dotted line is not too steep (more precisely, −𝜀 ⋅ a^{′−1} ≥ −1), then the graph makes it obvious that
the smallest U for which P_{U} is above or on the dotted line must lie on the line y = d − 1 = 4. In our case U = 64. For
Theorem 2, with a = 1 and b = 0, this means that U = P(d − 1). In particular, we are not interested in either P(n) or Q(n)

F I G U R E 16

```
               The geometry of replacing n∕d with a^{′} ⋅ n∕2^{k} , where d = 5 and k = 8, a^{′} = 2^{k} ∕d + 1 = 52
```

when n ≠ d − 1. This and b^{′} = 0 turn finding a fast EAF and its interval of applicability [0, U[ into an O(1) calculation
rather than an O(d) search as in the general case. These geometric ideas are present, although algebraically disguised, in
the proof of Theorem 4.
   The result of Theorem 4 also appears in Cavagnino and Werbrouck.^{5} In addition to providing the aforementioned
geometric insights and an arguably simpler proof, we favour faster algorithms over applicability range. In other words,
we reduce division to multiplication and bitwise shift and find the largest U for which this optimisation yields correct
results for all dividends in [0, U[. (In Cavagnino and Werbrouck,^{5} U is called critical value and is denoted by N_{cr} .)

```
                                                                                                  ⌉
                                                                                          ⌈
```

Theorem 4  (Fast division). Let d, k ∈ Z^{+} with d > 0. Set a^{′} = 2^{k} ∕d + 1, 𝜀 = d − 2^{k} %d and U = a^{′} ⋅ 𝜀^{−1} ⋅ d − 1. If 𝜀 ≤ a^{′},
then:

```
                                           n∕d = a^{′} ⋅ n∕2^{k} ,

                                                             ∀n ∈ [0, U[.
```

Proof. Let n ∈ [0, U[. From Lemma 2, it suffices to show that:

```
                                           0 ≤ 𝜀 ⋅ (n∕d) + a^{′} ⋅ (n%d) < 2^{k}

                                                                                                               (29)
```

Since 𝜀, a^{′} and n are non-negative, the first inequality above (positiveness) is trivially true. We shall prove the second

```
                                                                                                   (⌈
                                                                                                            ⌉
                                                                                                                )
```

inequality in two steps: n ≤ U − d and U − d + 1 ≤ n but, before this, note that U can be written as U = a^{′} ⋅ 𝜀^{−1} − 1 ⋅
d + (d − 1), which gives:

```
                                          ⌈
                                                 ⌉
                                   U∕d  =  a^{′} ⋅ 𝜀^{−1} − 1

                                                           and

                                                                   U%d  = d − 1.

                                                                                                               (30)
```

   Assume first that n ≤ U − d, so that n∕d ≤ U∕d − 1. We have:

        𝜀 ⋅ (n∕d) + a^{′} ⋅ (n%d) ≤ 𝜀 ⋅ (U∕d − 1) + a^{′} ⋅ (d − 1)

```
                                  (⌈
                                          ⌉
                                               )
                             = 𝜀 ⋅  a^{′} ⋅ 𝜀^{−1} − 2 + a^{′} ⋅ (d − 1)
                                  ⌈
                                         ⌉
                             = 𝜀 ⋅ a^{′} ⋅ 𝜀^{−1} − 2 ⋅ 𝜀 + a^{′} ⋅ d − a^{′}

                             < a^{′} + 𝜀 − 2 ⋅ 𝜀 + a^{′} ⋅ d − a^{′}

                                                                (from n∕d ≤  U∕d − 1 and  n%d ≤  d − 1)

                                                                (from Equation (30))

                                                                       ⌈
                                                                              ⌉
                                                                (from   a^{′} ⋅ 𝜀^{−1} < a^{′} ⋅ 𝜀^{−1} + 1)

                             = a^{′} ⋅ d − 𝜀

                                                                (from a^{′} ⋅ d = 2^{k} + 𝜀 as seen in Lemma 2.)

                             = 2^{k}
```

   ∴

        𝜀 ⋅ (n∕d) + a^{′} ⋅ (n%d) < 2^{k} .

   Now assume that U − d + 1 ≤ n < U and set r = n − (U − d + 1), so that 0 ≤ r < d − 1 and n = (U − d + 1) + r.
Therefore:

```
                              n = U  − (d − 1) + r

                                = U  − U%d  + r

                                                           (from Equation (30))

                                = (U∕d)  ⋅ d + r

                                                        (by division of U by d.)
```

Therefore n∕d = U∕d and n%d = r and it follows that:

          𝜀 ⋅ (n∕d) + a^{′} ⋅ (n%d) ≤ 𝜀 ⋅ (U∕d) + a^{′} ⋅ (d − 2)

```
                                                                           (since r ≤ d − 2)

                              = 𝜀 ⋅ (U∕d − 1) + a ⋅ (d − 1) + 𝜀 − a

                                                                           (by subtracting and adding 𝜀)

                              ≤ 𝜀 ⋅ (U∕d − 1) + a ⋅ (d − 1)

                                                                           (from assumption: 𝜀 ≤ a^{′})

                              < 2^{k}

                                                                           (similar to the case n ≤ U − d.)

                                                ′

                                                ′

                                                                 ′

                                                                                                                 ▪
```

Remark 2. If d is not a power of two and 2^{k} ≥ d ⋅ (d − 2), then 𝜀 ≤ a^{′}. Indeed, under these hypotheses we have 2^{k} ∕d ≥ d − 2
and 2^{k} %d ≥ 1. It follows that d − 2^{k} %d ≤ d − 1 ≤ 2^{k} ∕d + 1, that is, 𝜀 ≤ a^{′}.

   We have already applied Theorem 4 in the derivation of Algorithm 5 and the next example shows other divisions that
frequently appear in time calculations and decimal expansions.

Example 14 (Time calculations).

```
                      n∕3600  = 1193047  ⋅ n∕2^{32},

                                                                        ∀n ∈  [0, 2257199[,

                         n∕60 = 71582789  ⋅ n∕2 ,

                                                                        ∀n ∈  [0, 97612919[,

                         n∕10 = 429496730  ⋅ n∕2 ,

                                                                        ∀n ∈  [0, 1073741829[.

                                              32

                                                32
```

The first two lines can be used in conversions of seconds elapsed since midnight, a quantity in [0, 86400[, to hours, minutes and seconds. The third line can be used in conversions of non-negative integers up to 9 digits into their decimal
representations.

### 14.3 Fast evaluation of residual functions

For ease of reference, we restate Theorem 5 here and then provide its proof.

Theorem 5  (Alternative residual evaluation). Let f (n) = (a ⋅ n + b)∕d and f^{′}(n) = (a^{′} ⋅ n + b^{′})∕d^{′}, with d ≥ a > 0, and
assume that f (n) = f^{′}(n) for all n ∈ [L, U[. If f^{∗}(f (L)) = L and f^{′}(L − 1) < f^{′}(L), then:

```
                                            f ◦(n) = f ◦^{′}(n) ∀n ∈ [L, U[.
```

Proof. Let n ∈ [L, U[, so that f (n) = f^{′}(n) and set q = f (n) = f^{′}(n). Theorem 6-(3) gives f ◦(n) = n − f^{∗}(q) and we will show
that f ◦^{′}(n) = n − f^{∗}(q) to conclude the proof. To do that, we will use Lemma 1 with f^{′} instead of f and g = f^{∗}, that is, it
requires us to show the following:

(1) f^{′}(n) = f^{′}(f^{∗}(q)); and
(2) f^{′}(f^{∗}(q) − 1) < f^{′}(f^{∗}(q)).

   We will first show that f^{∗}(q) ∈ [L, U[. Since d ≥ a > 0, both f and f^{∗} are non-decreasing. Hence, from L ≤ n ≤ U −
1, we obtain f^{∗}(f (L)) ≤ f^{∗}(f (n)) ≤ f^{∗}(f (U − 1)). By assumption, L = f^{∗}(f (L)) and Theorem 6-(3) (for n = U − 1) gives, in
particular, f^{∗}(f (U − 1)) ≤ U − 1. Therefore, L ≤ f^{∗}(f (n)) ≤ U − 1, in other words, f^{∗}(q) ∈ [L, U[.
   Item 14.3 is obtained as follows:

```
                                                        (from f ≡ f^{′} on [L, U[ and f^{∗}(q) ∈ [L, U[)

                   f^{′}(f^{∗}(q)) = f (f^{∗}(q))

                                                        (from Theorem   6-(1))

                            = q

                                                                                                                (31)
```

   Since f^{∗}(q) ∈ [L, U[ either f^{∗}(q) = L or f^{∗}(q) − 1 ∈ [L, U[. In the former case, the assumption f^{′}(L − 1) < f^{′}(L) reads
f (f^{∗}(q) − 1) < f^{′}(f^{∗}(q)), which is Item 14.3. In the latter case we have:

 ′

```
                                                        (from f ≡ f^{′} on [L, U[ and f^{∗}(q) − 1 ∈ [L, U[)

               f^{′}(f^{∗}(q) − 1) = f (f^{∗}(q) − 1)

                            = q − 1

                                                        (from Theorem   6-(1))

                            < q

                            = f^{′}(f^{∗}(q))

                                                        (from Eq. (31).)

                                                                                                                  ▪
```

This concludes Item 14.3.

Example 15. Expanding on Example 14, Theorem 5 gives:

```
                  n%3600  =  1193047 ⋅ n%2^{32}∕1193047,

                                                                            ∀n  ∈ [0, 2257199[,

                    n%60  =  71582789 ⋅ n%2  ∕71582789,

                                                                            ∀n  ∈ [0, 97612919[,

                    n%10  =  429496730 ⋅ n%2  ∕429496730,

                                                                            ∀n  ∈ [0, 1073741829[.

                                           32

                                             32
```

           Quick remainder – the special case a = 1, b = 0

14.4

Again for this particular case, the EAF f (n) = (a ⋅ n + b)∕d simplifies to f (n) = n∕d and its residual function simplifies to
f ◦(n) = n%d. This section uses Theorem 4 and Theorem 5 to derive an efficient way to calculate remainders.
   Formally, Theorem 4 states how to replace n∕d with a^{′} ⋅ n∕2^{k} , where a^{′} ≈ 2^{k} ⋅ d^{−1} and k ∈ Z^{+}. Theorem 5 then gives
the equality n%d = (a^{′} ⋅ n%2^{k} )∕a^{′} and Theorem 4, again, suggests replacing division by a^{′} with multiplication by an
approximation of 2^{k} ⋅ a^{′−1} and division by 2^{k} . It turns out that d ≈ 2^{k} ⋅ a^{′−1} is the approximation we need and we obtain
n%d = d ⋅ (a^{′} ⋅ n%2^{k} )∕2^{k} . This is the idea behind our next theorem.

```
                                                                              ⌈
                                                                                      ⌉
```

Theorem 7. Let d, k ∈ Z^{+} with d > 0. Set a^{′} = 2^{k} ∕d + 1, 𝜀 = d − 2^{k} %d and U^{′} = 2^{k} ⋅ 𝜀^{−1} . If 𝜀 ≤ a^{′}, then:

```
                                       n%d =  d ⋅ (a^{′} ⋅ n%2^{k} )∕2^{k} ,

                                                                 ∀n  ∈ [0, U^{′}[.

                                                                                                                (32)

               ⌈
                       ⌉
```

Proof. Set U = a^{′} ⋅ 𝜀^{−1} ⋅ d − 1 as in Theorem 4. We have:

```
                                                             ⌉
                                                     ⌈
                                              (since  a^{′} ⋅ 𝜀^{−1} ≥ a^{′} ⋅ 𝜀^{−1})

                  U ≥ a^{′} ⋅ 𝜀^{−1} ⋅ d − 1

                    = (2^{k} + 𝜀) ⋅ 𝜀^{−1} − 1

                                              (since a^{′} ⋅ d = 2^{k} + 𝜀 from Lemma 2)
```

        ∴

```
                    = 2^{k} ⋅ 𝜀^{−1}.
                      ⌈
                              ⌉
                  U ≥  2^{k} ⋅ 𝜀^{−1}

                                              (since U is integer and greater than or equal to 2^{k} ⋅ 𝜀^{−1})
```

        ∴

```
                  U ≥ U^{′}

                                              (by definition of U^{′}.)
```

From the above and Theorem 4, we obtain:

```
                                           n∕d =  a^{′} ⋅ n∕2^{k} ,

                                                             ∀n ∈ [0, U^{′}[.

                                                                                                                (33)
```

   Hence, for f (n) = n∕d and f^{′}(n) = a^{′} ⋅ n∕2^{k} , Equation (33) states that f ≡ f^{′} on [0, U^{′}[. Simple calculations give
f (f (0)) = 0 and f^{′}(−1) < f^{′}(0). Therefore, Theorem 5 (for a = 1, b = 0 and L = 0) yields:

 ∗

```
                                        n%d  = (a^{′} ⋅ n%2^{k} )∕a^{′},

                                                                ∀n ∈ [0, U^{′}[.

                                                                                                                (34)
```

   Let n ∈ [0, U^{′}[ and set m = 𝜀 ⋅ (n∕d) + a^{′} ⋅ (n%d). Equation (33) and Lemma 2 give:

```
                               0 ≤ 𝜀 ⋅ (n∕d) + a^{′} ⋅ (n%d) < 2^{k} ,

                                                                  i.e.,

                                                                          0 ≤ m < 2^{k} .

                                                                                                                (35)
```

We have:

```
                          [
                                          ]
               a^{′} ⋅ n = a^{′} ⋅ d ⋅ (n∕d) + n%d

                                                             by division of n by d)

                     = a ⋅ d ⋅ (n∕d) + a ⋅ (n%d)

                        ′
```

     ∴

     ∴

```
                                       ′

                     = (2^{k} + 𝜀) ⋅ (n∕d) + a^{′} ⋅ (n%d)
                                   [
                                                       ]
                     = 2^{k} ⋅ (n∕d) + 𝜀 ⋅ (n∕d) + a^{′} ⋅ (n%d)

                                                             (since a^{′} ⋅ d = 2^{k} + 𝜀 from Lemma 2)

                     = 2^{k} ⋅ (n∕d) + m

                                                             (by definition of m.)
```

           a ⋅ n%2 = m

             ′

```
                   k

                n%d  = m∕a^{′}

                                                             (by division of a^{′} ⋅ n by 2^{k} and Equation (35).)

                                                             (from Equation  (34).)

                                                                                                                (36)
```

 We will show that m∕a^{′} = d ⋅ m∕2^{k} and, thus, from the last two equations above, it will follow that n%d = d ⋅ (a^{′} ⋅
n%2^{k} )∕2^{k} , which concludes this theorem.
   To show that m∕a^{′} = d ⋅ m∕2^{k} , we will apply Lemma 2 but “it is a perversity, not of the authors, but of nature”^{###}
that the symbols a^{′} and d therein and here are in interchanged positions. We must therefore verify the two inequalities
of Equation (28), not only with m in place of n but, annoyingly, a^{′} in place of d and vice-versa. In addition, we must use
a “different” 𝜀 defined by a^{′} − 2^{k} %a^{′}. The good news is that 𝜀 is, actually, the same. We have:

\###

  We borrowed this phrase from Paul Richard Halmos.

```
                     2^{k} = d ⋅ a^{′} − 𝜀

                                                        (since a^{′} ⋅ d = 2^{k} + 𝜀 from Lemma 2)

                        = (d − 1) ⋅ a^{′} + (a^{′} − 𝜀)

                                                        (by subtracting and adding a^{′}).

                                                                                                            (37)
```

Now, since 𝜀 = d − 2^{k} %d we have 𝜀 > 0 and, by assumption, 𝜀 ≤ a^{′}. Hence, 0 ≤ a^{′} − 𝜀 < a^{′}. From this, Equation (37) and
division by a^{′} we obtain 2^{k} ∕a^{′} = d − 1 and 2^{k} %a^{′} = a^{′} − 𝜀. In other words, d = 2^{k} ∕a^{′} + 1 and 𝜀 = a^{′} − 2^{k} %a^{′}. Note that
these two expressions are the same set by Lemma 2, again, with a^{′} and d interchanged. Therefore, to apply this lemma
and conclude the proof, we need to show that:

```
                                         0 ≤ 𝜀 ⋅ (m∕a^{′}) + d ⋅ (m%a^{′}) < 2^{k} .

                                                                                                            (38)
```

   From m = 𝜀 ⋅ (n∕d) + a^{′} ⋅ (n%d) and Equation (36) we obtain m∕a^{′} = n%d and m%a^{′} = 𝜀 ⋅ (n∕d). Therefore:

      0 ≤ 𝜀 ⋅ (m∕a^{′}) + d ⋅ (m%a^{′}) = 𝜀 ⋅ (n%d) + d ⋅ 𝜀 ⋅ (n∕d)

```
                                     [
                                                    ]
                                = 𝜀 ⋅ n%d + d ⋅ (n∕d)

                                                                  (from m∕a^{′} = n%d and 𝜀 ⋅ (n∕d) = m%a^{′})

                                = 𝜀 ⋅ n

                                < 2 .

                                                             (otherwise n ≥ 2 ⋅ 𝜀

                                   k

                                                                             k

                                                                                 −1

                                                                                    (by division of n by d)
                                                                                                        ⌉
                                                                                                ⌈
                                                                                   and then, n ≥ 2^{k} ⋅ 𝜀^{−1}

                                                                           = U^{′} which contradicts n < U^{′}.)

                                                                                                              ▪
```

Which proves Equation (38) as required.

Example 16. Revisiting Example 15 and using Theorem 7 gives:

```
                  n%3600  = 3600 ⋅ (1193047 ⋅ n%2^{32})∕2^{32},

                                                                         ∀n ∈ [0, 2255761[;

                    n%60  = 60 ⋅ (71582789 ⋅ n%2 )∕2 ,

                                                                         ∀n ∈ [0, 97612894[;

                    n%10  = 10 ⋅ (429496730 ⋅ n%2 )∕2 ,

                                                                         ∀n ∈ [0, 1073741824[.

                                               32

                                                32

                                                    32

                                                     32
```

The expressions on the right side of the equals sign provide efficient ways of evaluating remainders. However, greater
benefits are achieved when they are used in conjunction with the quotient expressions presented in Example 14.

   The equality in Equation (32) also appears in Lemire et al.^{9} As in other works, it focuses on obtaining the value k ∈ Z^{+}
for which the equality holds on an interval of the form [0, 2^{w}[ or, in other words, 2^{w} ≤ U^{′} as the next result shows.

Corollary 1. Let d, l, w ∈ Z^{+} with 0 < d < 2^{w} and d − 2^{w+l}%d ≤ 2^{l}. Set a^{′} = 2^{w+l}∕d + 1. Then,

```
                                   n%d  = d ⋅ (a^{′} ⋅ n%2^{w+l})∕2^{w+l},

                                                                 ∀n ∈ [0, 2^{w}[.

                                          ⌈
                                                 ⌉
```

Proof. Set k = w + l, 𝜀 = d − 2^{k} %d and U^{′} = 2^{k} ⋅ 𝜀^{−1} . From Theorem 7, it is sufficient to show that 𝜀 ≤ a^{′} and 2^{k} ≤ U^{′}.
Since d < 2^{w} and 𝜀 ≤ 2^{l}, we have d ⋅ 𝜀 < 2^{w+l} = 2^{k} and thus, 𝜀 ≤ 2^{k} ∕d < a^{′}. We also have U^{′} ≥ 2^{k} ⋅ 𝜀^{−1} ≥ 2^{k} ⋅ 2^{−l} = 2^{w}. ▪

ACKNOWLEDGMENTS
We thank the editor, Dr. Daniel Lemire, and an anonymous referee for their detailed review and insightful suggestions,
which helped us to improve the quality of our article. We are also grateful to Cristina Acosta and Becky Rawlings for their
continuing support and helpful feedback.

DATA AVAILABILITY STATEMENT
Data sharing is not applicable to this article as no new data were created or analyzed in this study.

AU THOR CONTRIBUTIONS
Conceptualization, methodology, investigation and writing were performed by Cassio Neri and Lorenz Schneider. Coding
was done by Cassio Neri. Funding for the project was acquired by Lorenz Schneider.

ORCID
Cassio Neri https://orcid.org/0000-0001-6940-188X
Lorenz Schneider https://orcid.org/0000-0001-5278-8184

REFERENCES

 1.
 2.
 3.
 4.
 5.
 6.

 7.

 8.

 9.

10.
11.
12.
13.
14.
15.
16.
17.
18.
19.
20.
21.
22.
23.

24.
25.

26.
27.
28.
29.

    Richards EG. Mapping Time: The Calendar and its History. Oxford University Press; 1998.
    Duncan DE. The Calendar: The 5000-Year Struggle to Align the Clock and the Heavens. 1st ed. Fourth Estate; 1998.
    Duncan S. Marking Time: The Epic Quest to Invent the Perfect Calendar. 1st ed. John Wiley & Sons; 2000.
    Alverson R. Integer division using reciprocals. Proceedings of the 10th IEEE Symposium on Computer Arithmetic. 1991; pp. 186-190.
    Cavagnino D, Werbrouck AE. Efficient algorithms for integer division by constants using multiplication. Comput J. 2007;51(4):470-480.
    Granlund T, Montgomery PL. Division by invariant integers using multiplication. Proceedings of the ACM SIGPLAN. Conference on
    Programming Language Design and Implementation: 61–72; 1994. New York, NY, USA; 1994.
    Magenheimer DJ, Peters L, Pettis KW, Zuras D. Integer multiplication and division on the HP precision architecture. IEEE Trans Comput.
    1988;37(8):980-990.
    Robison AD. N-bit unsigned division via n-bit multiply-add. Proceedings of the 17th IEEE Symposium on Computer Arithmetic (ARITH’05).
    IEEE; 2005:131-139.
    Lemire D, Kaser O, Kurz N. Faster remainder by direct computation: applications to compilers and software libraries. Software Pract Exp.
    2019;49(6):953-970.
    Warren HS. Hacker’s Delight. 2nd ed. Addison-Wesley Professional; 2013.
    GNU C Library. time/offtime.c. 2020. https://tinyurl.com/yyr7uazb
    Microsoft.NET. DateTime.cs. 2020. https://tinyurl.com/y4kej3mmsrc/libraries/system.private.corelib/src/system/datetime.cs
    Baum P. Date algorithms. 1998. https://tinyurl.com/y44rgx2j
    Fliegel HF, Flandern TC. Letters to the editor: a machine algorithm for processing Calendar dates. Commun ACM. 1968;11(10):657-658.
    Hatcher DA. Simple formulae for Julian day numbers and Calendar dates. Q J R Astron Soc. 1984;25(1):53-55.
    Hatcher DA. Generalized equations for Julian day numbers and Calendar dates. Q J R Astron Soc. 1985;26(2):151-155.
    Boost C++ Libraries . include/boost/date_time/gregorian_calendar.ipp. 2020. https://tinyurl.com/y4buxmmf
    LLVM Project. libcxx/include/chrono. 2019. https://tinyurl.com/yytw67zb
    Reingold EM, Dershowitz N. Calendrical Calculations: The Ultimate. 4th ed. Cambridge University Press; 2018.
    GNU C Library. time/offtime.c. https://tinyurl.com/y42pkbhp
    OpenJDK. LocalDate.java. jdk/src/java.base/share/classes/java/time/LocalDate.java, 2019. https://tinyurl.com/y92svzxw
    Android. ojluni/src/main/java/java/time/LocalDate.java. 2017. https://tinyurl.com/ycsltdnq
    Zeller C. Die Grundaufgaben der Kalenderrechnung auf neue und vereinfachte Weise gelöst. Württ Vierteljahrsh Landesgesch.
    1882;5:313-314.
    Troesch A. Droites discrètes et calendriers. Math Sci Humaines. 1998;141:11-41. doi:10.4000/msh.2760
    Neri C, Schneider L. Supplementary Material for Euclidean Affine Functions and their Application to Calendar Algorithms. 2022.
    https://github.com/cassioneri/eaf
    GNU Compiler Collection. GCC 11 Release Series, Changes, New Features, and Fixes. 2021. https://tinyurl.com/475dy6vx
    Linux Kernel. drivers/rtc/lib.c. 2021. https://tinyurl.com/mr764jm5
    Linux Kernel. kernel/time/timeconv.c. 2021. https://tinyurl.com/4hx3bzaw
    Google. Benchmark v1.5.2. 2020. https://tinyurl.com/y29mh7q5

    How to cite this article: Neri C, Schneider L. Euclidean affine functions and their application to
    calendar algorithms. Softw Pract Exper. 2023;53(4):937-970. doi: 10.1002/spe.3172

[Correction added on 21 December 2022, after first online publication: the format of the references were corrected in this version.]


