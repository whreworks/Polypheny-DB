/*
 * Copyright 2019-2024 The Polypheny Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.polypheny.db.cypher;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.StopWatch;
import org.polypheny.db.algebra.AlgDecorrelator;
import org.polypheny.db.algebra.AlgRoot;
import org.polypheny.db.algebra.constant.ExplainFormat;
import org.polypheny.db.algebra.constant.ExplainLevel;
import org.polypheny.db.algebra.type.AlgDataType;
import org.polypheny.db.catalog.Catalog;
import org.polypheny.db.catalog.entity.logical.LogicalNamespace;
import org.polypheny.db.catalog.exceptions.GenericRuntimeException;
import org.polypheny.db.cypher.cypher2alg.CypherToAlgConverter;
import org.polypheny.db.cypher.parser.CypherParser;
import org.polypheny.db.cypher.parser.CypherParser.CypherParserConfig;
import org.polypheny.db.languages.NodeParseException;
import org.polypheny.db.languages.QueryParameters;
import org.polypheny.db.nodes.Node;
import org.polypheny.db.plan.AlgCluster;
import org.polypheny.db.plan.AlgOptUtil;
import org.polypheny.db.processing.Processor;
import org.polypheny.db.processing.QueryContext.ParsedQueryContext;
import org.polypheny.db.rex.RexBuilder;
import org.polypheny.db.tools.AlgBuilder;
import org.polypheny.db.transaction.Statement;
import org.polypheny.db.transaction.Transaction;
import org.polypheny.db.transaction.locking.Lockable.LockType;
import org.polypheny.db.transaction.locking.LockablesRegistry;
import org.polypheny.db.util.DeadlockException;
import org.polypheny.db.util.Pair;

@Slf4j
public class CypherProcessor extends Processor {

    private static final CypherParserConfig parserConfig;


    static {
        CypherParser.ConfigBuilder configConfigBuilder = CypherParser.configBuilder();
        parserConfig = configConfigBuilder.build();
    }


    @Override
    public List<CypherStatement> parse( String query ) {
        final StopWatch stopWatch = new StopWatch();
        if ( log.isDebugEnabled() ) {
            log.debug( "Parsing PolyMQL statement ..." );
        }
        stopWatch.start();
        List<CypherStatement> parsed;
        if ( log.isDebugEnabled() ) {
            log.debug( "CYPHER: {}", query );
        }

        try {
            final CypherParser parser = CypherParser.create( query, parserConfig );
            parsed = parser.parseStmts();
        } catch ( NodeParseException e ) {
            log.error( "Caught exception", e );
            throw new GenericRuntimeException( e );
        }
        stopWatch.stop();
        if ( log.isTraceEnabled() ) {
            log.trace( "Parsed query: [{}]", parsed );
        }
        if ( log.isDebugEnabled() ) {
            log.debug( "Parsing PolyCypher statement ... done. [{}]", stopWatch );
        }
        return parsed;
    }


    @Override
    public Pair<Node, AlgDataType> validate( Transaction transaction, Node parsed, boolean addDefaultValues ) {
        throw new GenericRuntimeException( "The Cypher implementation does not support validation." );
    }


    @Override
    public AlgRoot translate( Statement statement, ParsedQueryContext context ) {

        final StopWatch stopWatch = new StopWatch();
        if ( log.isDebugEnabled() ) {
            log.debug( "Planning Statement ..." );
        }
        stopWatch.start();

        final AlgBuilder builder = AlgBuilder.create( statement );
        final RexBuilder rexBuilder = new RexBuilder( statement.getTransaction().getTypeFactory() );
        final AlgCluster cluster = AlgCluster.createGraph( statement.getQueryProcessor().getPlanner(), rexBuilder, statement.getDataContext().getSnapshot() );

        final CypherToAlgConverter cypherToAlgConverter = new CypherToAlgConverter( statement, builder, rexBuilder, cluster );

        AlgRoot logicalRoot = cypherToAlgConverter.convert( (CypherNode) context.getQueryNode().orElseThrow(), context, cluster );

        // Decorrelate
        final AlgBuilder algBuilder = AlgBuilder.create( statement );
        logicalRoot = logicalRoot.withAlg( AlgDecorrelator.decorrelateQuery( logicalRoot.alg, algBuilder ) );

        if ( log.isTraceEnabled() ) {
            log.trace( "Logical query plan: [{}]", AlgOptUtil.dumpPlan( "-- Logical Plan", logicalRoot.alg, ExplainFormat.TEXT, ExplainLevel.DIGEST_ATTRIBUTES ) );
        }
        stopWatch.stop();
        if ( log.isDebugEnabled() ) {
            log.debug( "Planning Statement ... done. [{}]", stopWatch );
        }

        return logicalRoot;
    }


    @Override
    protected void lock( Transaction transaction, ParsedQueryContext context ) throws DeadlockException {
        // exclusive lock
        LogicalNamespace namespace = Catalog.getInstance().getSnapshot().getNamespace( context.getNamespaceId() ).orElseThrow();
        transaction.acquireLockable( LockablesRegistry.INSTANCE.getOrCreateLockable( namespace ), LockType.EXCLUSIVE );
    }


    @Override
    public String getQuery( Node parsed, QueryParameters parameters ) {
        return parameters.getQuery();
    }


    @Override
    public AlgDataType getParameterRowType( Node left ) {
        return null;
    }


    @Override
    public List<String> splitStatements( String statements ) {
        List<String> split = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Deque<Character> expectedClosings = new ArrayDeque<>();
        char quote = 0;

        for ( int i = 0; i < statements.length(); i++ ) {
            char ch = statements.charAt( i );

            if ( quote != 0 ) {
                if ( ch == '\\' && i + 1 < statements.length() ) {
                    current.append( ch ).append( statements.charAt( ++i ) );
                    continue;
                }
                if ( ch == quote ) {
                    if ( i + 1 < statements.length() && statements.charAt( i + 1 ) == quote ) {
                        current.append( quote ).append( quote );
                        i++;
                        continue;
                    }
                    quote = 0;
                }
                current.append( ch );
                continue;
            }

            switch ( ch ) {
                case '\'', '"', '`' -> {
                    quote = ch;
                    current.append( ch );
                }
                case '(' -> {
                    expectedClosings.push( ')' );
                    current.append( ch );
                }
                case '[' -> {
                    expectedClosings.push( ']' );
                    current.append( ch );
                }
                case '{' -> {
                    expectedClosings.push( '}' );
                    current.append( ch );
                }
                case ')', ']', '}' -> {
                    if ( expectedClosings.isEmpty() || expectedClosings.pop() != ch ) {
                        throw new GenericRuntimeException( "Mismatch " + ch + "found" );
                    }
                    current.append( ch );
                }
                case ';' -> {
                    if ( expectedClosings.isEmpty() ) {
                        throw new GenericRuntimeException( "Missing closing '" + expectedClosings.pop() + "'" );
                    }
                    addIfNotBlank( split, current );
                    current.setLength( 0 );
                }
                case '/' -> {
                    if ( i + 1 < statements.length() && statements.charAt( i + 1 ) == '/' ) {
                        while ( i + 1 < statements.length() && statements.charAt( i + 1 ) != '\n' ) {
                            i++;
                        }
                        if ( i + 1 < statements.length() ) {
                            i++;
                        }
                        current.append( ' ' );
                    } else if ( i + 1 < statements.length() && statements.charAt( i + 1 ) == '*' ) {
                        int end = statements.indexOf( "*/", i + 2 );
                        if ( end < 0 ) {
                            throw new GenericRuntimeException( "Unterminaed block comment" );
                        }
                        i = end + 1;
                        current.append( ' ' );
                    } else {
                        current.append( ch );
                    }
                }
                default -> current.append( ch );
            }
        }
        if ( quote != 0 ) {
            throw new GenericRuntimeException( "Unterminated " + quote );
        }
        if ( !expectedClosings.isEmpty() ) {
            throw new GenericRuntimeException( "Missing closing " + expectedClosings.pop());
        }
        addIfNotBlank( split, current );
        return split.stream().map( String::strip ).toList();
    }

    private static void addIfNotBlank( List<String> split, StringBuilder statement ) {
        if ( !statement.toString().isBlank() ) {
            split.add( statement.toString() );
        }
    }

}
